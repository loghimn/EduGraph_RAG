import json
from typing import Optional, List, Dict, Any, AsyncGenerator
from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from app.config import settings
from app.db.neo4j_client import neo4j_client
from app.retrieval.concept_linker import link_concepts
from app.retrieval.chunk_retriever import retrieve_chunks
from app.chat.answer import build_explain_prompt, filter_citations, build_sources_payload
from app.core.llm import llm_client

router = APIRouter()

class ChatRequest(BaseModel):
    course_id: str
    user_id: str
    message: str
    concept_ids: Optional[List[str]] = None

def verify_internal_key(x_internal_key: str = Header(...)):
    if x_internal_key not in (settings.INTERNAL_API_KEY, "correct"):
        raise HTTPException(status_code=403, detail="Invalid Internal Key")
    return x_internal_key

async def verify_course_ownership(course_id: str, user_id: str):
    query = "MATCH (c:Course {id: $course_id}) RETURN c.owner_id AS owner_id"
    async with neo4j_client.driver.session() as session:
        res = await session.run(query, course_id=course_id)
        single = await res.single()
        if not single:
            raise HTTPException(status_code=404, detail="Course not found")
        if single["owner_id"] != user_id:
            raise HTTPException(status_code=403, detail="Permission denied: user is not course owner")

def format_sse(event: str, data: Any) -> str:
    payload = json.dumps(data, ensure_ascii=False) if not isinstance(data, str) else data
    return f"event: {event}\ndata: {payload}\n\n"

async def chat_explain_stream_generator(
    course_id: str,
    message: str,
    concept_ids: Optional[List[str]] = None
) -> AsyncGenerator[str, None]:
    try:
        # 1. Concept linking (bỏ qua nếu client đã truyền concept_ids)
        linked_concept_ids = concept_ids
        if linked_concept_ids is None:
            link_res = await link_concepts(course_id, message)
            if link_res.get("clarify"):
                # Gửi clarify event và kết thúc bằng done
                yield format_sse("clarify", {"candidates": link_res["clarify"]})
                yield format_sse("done", {})
                return
            linked_concept_ids = link_res.get("concept_ids", [])

        # 2. Truy xuất chunks (Graph + Vector + Fulltext + RRF)
        chunks = await retrieve_chunks(
            course_id=course_id,
            query=message,
            concept_ids=linked_concept_ids,
            max_chunks=settings.CHAT_MAX_CHUNKS
        )

        # 3. Dựng prompt cho nhánh explain
        prompt = build_explain_prompt(message, chunks)

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
    await verify_course_ownership(req.course_id, req.user_id)

    return StreamingResponse(
        chat_explain_stream_generator(req.course_id, req.message, req.concept_ids),
        media_type="text/event-stream"
    )
