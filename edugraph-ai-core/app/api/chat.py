import json
import asyncio
import logging
from typing import Optional, List, Dict, Any, AsyncGenerator, Literal
from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.config import settings
from app.api.internal_auth import verify_internal_key
from app.db.neo4j_client import neo4j_client
from app.retrieval.concept_linker import link_concepts
from app.retrieval.chunk_retriever import retrieve_chunks
from app.chat.answer import build_explain_prompt, filter_citations, build_sources_payload
from app.core.llm import llm_client
from app.core.errors import LLMUnavailableError
from app.chat.context import prepare_history, resolve_question

router = APIRouter()
logger = logging.getLogger(__name__)

NO_DATA_MESSAGE = (
    "Không tìm thấy dữ liệu phù hợp với câu hỏi trong tài liệu của khóa học. "
    "Bạn thử hỏi cụ thể hơn nhé."
)


def no_data_events() -> List[str]:
    # Keep the existing protocol so Java persists this as a normal BOT answer.
    return [
        format_sse("token", {"token": NO_DATA_MESSAGE}),
        format_sse("sources", []),
        format_sse("done", {}),
    ]

class HistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=4_000)


class ChatRequest(BaseModel):
    course_id: str
    user_id: str
    message: str
    concept_ids: Optional[List[str]] = None
    history: List[HistoryMessage] = Field(default_factory=list, max_length=12)
    reply_context: List[HistoryMessage] = Field(default_factory=list, max_length=2)

async def verify_course_exists(course_id: str):
    # Java authorizes owners/enrolled learners against its relational database.
    # This internal endpoint trusts that decision only after verifying the key.
    query = "MATCH (c:Course {id: $course_id}) RETURN c.id AS id"
    async with neo4j_client.driver.session() as session:
        res = await session.run(query, course_id=course_id)
        single = await res.single()
        if not single:
            raise HTTPException(status_code=404, detail="Course not found")

def format_sse(event: str, data: Any) -> str:
    payload = json.dumps(data, ensure_ascii=False) if not isinstance(data, str) else data
    return f"event: {event}\ndata: {payload}\n\n"

async def chat_explain_stream_generator(
    course_id: str,
    message: str,
    concept_ids: Optional[List[str]] = None,
    history: Optional[List[Dict[str, str]]] = None,
    reply_context: Optional[List[Dict[str, str]]] = None
) -> AsyncGenerator[str, None]:
    try:
        history = prepare_history(history)
        reply_context = prepare_history(reply_context)
        search_query = message
        if history or reply_context:
            yield ": resolving conversation context\n\n"
            try:
                resolution = (resolve_question(message, history, reply_context) if reply_context
                              else resolve_question(message, history))
                search_query = await asyncio.wait_for(resolution, timeout=20)
            except (ValueError, LLMUnavailableError, asyncio.TimeoutError) as error:
                # Context rewriting is optional; never invent a replacement query.
                search_query = message
                logger.warning("Context resolution failed; using original question. error_type=%s",
                               type(error).__name__)
                yield ": using original question\n\n"
            else:
                yield ": conversation context resolved\n\n"
        # 1. Concept linking (bỏ qua nếu client đã truyền concept_ids)
        linked_concept_ids = concept_ids
        if linked_concept_ids is None:
            link_res = await link_concepts(course_id, search_query)
            if link_res.get("clarify"):
                # Gửi clarify event và kết thúc bằng done
                yield format_sse("clarify", {"candidates": link_res["clarify"]})
                yield format_sse("done", {})
                return
            linked_concept_ids = link_res.get("concept_ids", [])

        if not linked_concept_ids:
            for event in no_data_events():
                yield event
            return

        # 2. Truy xuất chunks (Graph + Vector + Fulltext + RRF)
        chunks = await retrieve_chunks(
            course_id=course_id,
            query=search_query,
            concept_ids=linked_concept_ids,
            max_chunks=settings.CHAT_MAX_CHUNKS
        )

        if not chunks:
            for event in no_data_events():
                yield event
            return

        # 3. Dựng prompt cho nhánh explain
        prompt = build_explain_prompt(message, chunks, history=history, resolved_question=search_query,
                                      reply_context=reply_context)

        # 4. Stream token từ LLM (với priority=True)
        accumulated_tokens = []
        has_error = False

        async for token in llm_client.generate_stream(prompt, priority=True):
            if token.startswith("\n\n[ERROR:"):
                # Lỗi giữa chừng
                has_error = True
                yield format_sse("error", {"error": token.strip()})
                break
            accumulated_tokens.append(token)
            yield format_sse("token", {"token": token})

        # 5. Lọc trích dẫn [n] và gửi sources
        if not has_error:
            full_text = "".join(accumulated_tokens)
            _, cited_indices = filter_citations(full_text, len(chunks))
            sources = build_sources_payload(cited_indices, chunks)
            yield format_sse("sources", sources)

        # 6. Kết thúc luồng
        yield format_sse("done", {})

    except Exception as e:
        yield format_sse("error", {"error": str(e)})
        yield format_sse("done", {})

@router.post("/chat")
async def chat_endpoint(
    req: ChatRequest,
    x_internal_key: str = Header(...)
):
    verify_internal_key(x_internal_key)
    await verify_course_exists(req.course_id)

    return StreamingResponse(
        chat_explain_stream_generator(req.course_id, req.message, req.concept_ids,
                                      [item.model_dump() for item in req.history],
                                      [item.model_dump() for item in req.reply_context]),
        media_type="text/event-stream"
    )
