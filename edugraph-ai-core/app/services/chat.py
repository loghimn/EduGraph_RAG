from app.db.queries import chat as queries
from app.schemas.chat import ChatRequest, GraphRelationship
import json
import asyncio
import logging
from typing import Optional, List, Dict, Any, AsyncGenerator
from fastapi import HTTPException
from fastapi.responses import StreamingResponse

from app.config import settings
from app.db import graph_repository
from app.retrieval.concept_linker import link_concepts
from app.retrieval.chunk_retriever import retrieve_chunks
from app.retrieval.relationship_retriever import retrieve_relationship
from app.chat.answer import build_explain_prompt, filter_citations, build_sources_payload
from app.core.llm import llm_client
from app.core.errors import LLMUnavailableError
from app.chat.context import prepare_history, resolve_question
from app.chat.streaming import with_sse_heartbeats
from app.chat.path_intent import detect_path_intent, needs_topic_context
from app.services.chat_paths import build_chat_path

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


async def verify_course_exists(course_id: str):
    # Java authorizes owners/enrolled learners against its relational database.
    # This internal endpoint trusts that decision only after verifying the key.
    query = queries.VERIFY_COURSE_EXISTS_QUERY_1
    async with graph_repository.session() as session:
        res = await graph_repository.run(session, query, course_id=course_id)
        single = await res.single()
        if not single:
            raise HTTPException(status_code=404, detail="Course not found")

def format_sse(event: str, data: Any) -> str:
    payload = json.dumps(data, ensure_ascii=False) if not isinstance(data, str) else data
    return f"event: {event}\ndata: {payload}\n\n"

async def chat_explain_stream_generator(
    course_id: Optional[str],
    message: str,
    concept_ids: Optional[List[str]] = None,
    authorized_course_ids: Optional[List[str]] = None,
    history: Optional[List[Dict[str, str]]] = None,
    reply_context: Optional[List[Dict[str, str]]] = None,
    course_names: Optional[Dict[str, str]] = None,
    graph_relationship: Optional[GraphRelationship] = None,
    known_concepts_by_course: Optional[Dict[str, List[str]]] = None,
) -> AsyncGenerator[str, None]:
    events = _chat_explain_events(course_id, message, concept_ids, authorized_course_ids, history, reply_context,
                                  course_names, graph_relationship, known_concepts_by_course)
    stream = with_sse_heartbeats(events, settings.CHAT_HEARTBEAT_INTERVAL_SECONDS)
    try:
        async for frame in stream:
            yield frame
    finally:
        await stream.aclose()


def _authorized_scope(course_id, authorized_course_ids):
    if course_id is not None:
        return [course_id] if authorized_course_ids is None or course_id in authorized_course_ids else []
    return authorized_course_ids or []


async def _resolve_search_query(message, history, reply_context):
    try:
        resolution = (resolve_question(message, history, reply_context) if reply_context
                      else resolve_question(message, history))
        query = await asyncio.wait_for(resolution, timeout=20)
    except (ValueError, LLMUnavailableError, asyncio.TimeoutError) as error:
        logger.warning("Context resolution failed; using original question. error_type=%s", type(error).__name__)
        return message, ": using original question\n\n"
    return query, ": conversation context resolved\n\n"


def _needs_conversation_context(history, reply_context, path_intent):
    return (history or reply_context) and (path_intent is None or needs_topic_context(path_intent))


async def _path_events(path):
    if path.get("candidates"):
        yield format_sse("clarify", {"candidates": path["candidates"]})
    else:
        answer, indices = filter_citations(path["answer"], len(path["chunks"]))
        for offset in range(0, len(answer), 256):
            yield format_sse("token", {"token": answer[offset:offset + 256]})
        yield format_sse("answer", {"content": answer})
        yield format_sse("sources", build_sources_payload(indices, path["chunks"]))
    yield format_sse("done", {})


async def _link_question(authorized_course_ids, query, concept_ids, course_names):
    if concept_ids is not None:
        return concept_ids, None
    if course_names:
        scoped_names = {cid: course_names[cid] for cid in authorized_course_ids if cid in course_names}
        result = await link_concepts(authorized_course_ids, query, course_names=scoped_names)
    else:
        result = await link_concepts(authorized_course_ids, query)
    return result.get("concept_ids", []), result.get("clarify")


async def _answer_events(prompt, chunks):
    accumulated_tokens = []
    has_error = False
    async for token in llm_client.generate_stream(prompt, priority=True):
        if token.startswith("\n\n[ERROR:"):
            has_error = True
            yield format_sse("error", {"error": token.strip()})
            break
        accumulated_tokens.append(token)
        yield format_sse("token", {"token": token})
    if not has_error:
        answer, indices = filter_citations("".join(accumulated_tokens), len(chunks))
        yield format_sse("answer", {"content": answer})
        yield format_sse("sources", build_sources_payload(indices, chunks))
    yield format_sse("done", {})


async def _explain_events(course_id, message, query, concept_ids, authorized_course_ids, course_names, conversation):
    linked_ids, candidates = await _link_question(authorized_course_ids, query, concept_ids, course_names)
    if candidates:
        yield format_sse("clarify", {"candidates": candidates[:50]})
        yield format_sse("done", {})
        return
    chunks = await retrieve_chunks(course_id=course_id, query=query, concept_ids=linked_ids,
                                   authorized_course_ids=authorized_course_ids, max_chunks=settings.CHAT_MAX_CHUNKS)
    relationship_context = None
    relationship = conversation["graph_relationship"]
    if relationship is not None:
        relationship_context, evidence = await retrieve_relationship(relationship, authorized_course_ids)
        evidence_ids = {item["id"] for item in evidence}
        chunks = (evidence + [item for item in chunks if item["id"] not in evidence_ids])[:settings.CHAT_MAX_CHUNKS]
    if not chunks:
        logger.info("Chat retrieval stopped: no chunks linked_concepts=%d", len(linked_ids or []))
        for event in no_data_events():
            yield event
        return
    prompt_args = {"history": conversation["history"], "resolved_question": query,
                   "reply_context": conversation["reply_context"]}
    if relationship_context is not None:
        prompt_args["relationship_context"] = relationship_context
    prompt = build_explain_prompt(message, chunks, **prompt_args)
    async for event in _answer_events(prompt, chunks):
        yield event


async def _chat_explain_events(
    course_id: Optional[str],
    message: str,
    concept_ids: Optional[List[str]] = None,
    authorized_course_ids: Optional[List[str]] = None,
    history: Optional[List[Dict[str, str]]] = None,
    reply_context: Optional[List[Dict[str, str]]] = None,
    course_names: Optional[Dict[str, str]] = None,
    graph_relationship: Optional[GraphRelationship] = None,
    known_concepts_by_course: Optional[Dict[str, List[str]]] = None,
) -> AsyncGenerator[str, None]:
    try:
        authorized_course_ids = _authorized_scope(course_id, authorized_course_ids)
        if not authorized_course_ids:
            logger.info("Chat retrieval stopped: no authorized courses")
            for event in no_data_events():
                yield event
            return
        history = prepare_history(history)
        reply_context = prepare_history(reply_context)
        search_query = message
        path_intent = detect_path_intent(message) if graph_relationship is None else None
        if _needs_conversation_context(history, reply_context, path_intent):
            yield ": resolving conversation context\n\n"
            search_query, resolution_event = await _resolve_search_query(message, history, reply_context)
            yield resolution_event
        if graph_relationship is None:
            path_intent = detect_path_intent(search_query) or path_intent
        if path_intent is not None:
            yield ": computing prerequisite learning path\n\n"
            path = await build_chat_path(path_intent, authorized_course_ids, concept_ids, course_names,
                                         known_concepts_by_course)
            async for event in _path_events(path):
                yield event
            return
        conversation = {"history": history, "reply_context": reply_context, "graph_relationship": graph_relationship}
        async for event in _explain_events(course_id, message, search_query, concept_ids,
                                           authorized_course_ids, course_names, conversation):
            yield event
    except Exception as error:
        yield format_sse("error", {"error": str(error)})
        yield format_sse("done", {})


async def chat_endpoint(req: ChatRequest):
    if req.course_id is not None:
        await verify_course_exists(req.course_id)

    return StreamingResponse(
        chat_explain_stream_generator(req.course_id, req.message, req.concept_ids, req.authorized_course_ids,
                                      [item.model_dump() for item in req.history],
                                      [item.model_dump() for item in req.reply_context], req.course_names, req.graph_relationship,
                                      req.known_concepts_by_course),
        media_type="text/event-stream"
    )

