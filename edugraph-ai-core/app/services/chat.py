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
from app.retrieval.named_relationship import resolve_named_relationship
from app.chat.answer import build_explain_prompt, filter_citations, build_sources_payload
from app.core.llm import llm_client
from app.core.errors import LLMUnavailableError
from app.chat.context import prepare_history, resolve_question
from app.chat.streaming import with_sse_heartbeats
from app.chat.path_intent import PathIntent, detect_path_intent, needs_topic_context
from app.chat.intent import classify_chat_intent
from app.services.chat_paths import build_chat_path
from app.retrieval.prerequisite_context import retrieve_prerequisite_context, merge_prerequisite_chunks
from app.services.course_discovery import discover_courses, invitation

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
    course_settings_by_course=None,
    public_courses=None,
) -> AsyncGenerator[str, None]:
    events = _chat_explain_events(course_id, message, concept_ids, authorized_course_ids, history, reply_context,
                                  course_names, graph_relationship, known_concepts_by_course, course_settings_by_course, public_courses)
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
        if path.get("targets"):
            yield format_sse("concepts", path["targets"])
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


def _suggestion_events(recommendations):
    return [format_sse('course_suggestions', recommendations),
            format_sse('answer', {'content': invitation(recommendations)}),
            format_sse('sources', []), format_sse('done', {})]


async def _fallback_events(course_id, query, concept_ids, authorized_course_ids, conversation):
    """Call only after ordinary in-scope retrieval and exact-edge evidence are exhausted."""
    if course_id is not None and conversation.get('public_courses'):
        recommendations, _ = await discover_courses(
            authorized_course_ids, conversation['public_courses'], query, concept_ids,
            conversation['graph_relationship'], conversation.get('target'), conversation.get('relationship_names'))
        if recommendations:
            return _suggestion_events(recommendations)
    return no_data_events()


def _retrieval_arguments(course_id, query, concept_ids, authorized_course_ids, configs):
    arguments = {"course_id": course_id, "query": query, "concept_ids": concept_ids,
                 "authorized_course_ids": authorized_course_ids, "max_chunks": settings.CHAT_MAX_CHUNKS}
    if configs:
        arguments['course_settings'] = configs
    return arguments


def _prefer_relationship_evidence(chunks, evidence):
    evidence_ids = {item['id'] for item in evidence}
    return (evidence + [item for item in chunks if item['id'] not in evidence_ids])[:settings.CHAT_MAX_CHUNKS]


def _prompt_arguments(course_id, query, chunks, configs, conversation, relationship_context, prerequisite_context):
    arguments = {"history": conversation['history'], "resolved_question": query,
                 "reply_context": conversation['reply_context']}
    if relationship_context is not None:
        arguments['relationship_context'] = relationship_context
    if prerequisite_context:
        arguments['prerequisite_context'] = prerequisite_context
    relevant_courses = {item['course_id'] for item in prerequisite_context or []}
    relevant_courses.update(item['course_id'] for item in chunks if item.get('course_id'))
    if course_id:
        relevant_courses.add(course_id)
    teaching = [configs[cid].prompt_template for cid in sorted(relevant_courses)
                if cid in configs and configs[cid].prompt_template]
    if teaching:
        arguments['teaching_instructions'] = '\n'.join(teaching)
    return arguments


async def _explain_events(course_id, message, query, concept_ids, authorized_course_ids, course_names, conversation):
    relationship = conversation['graph_relationship']
    relationship_context = None
    names = conversation.get('relationship_names')
    if relationship is None and names is not None:
        relationship, relationship_context, concept_ids = await resolve_named_relationship(names, authorized_course_ids)
    linked_ids, candidates = await _link_question(authorized_course_ids, query, concept_ids, course_names)
    if candidates:
        yield format_sse("clarify", {"candidates": candidates[:50]})
        yield format_sse("done", {})
        return
    configs = conversation.get("course_settings_by_course") or {}
    retrieval_args = _retrieval_arguments(course_id, query, linked_ids, authorized_course_ids, configs)
    chunks = await retrieve_chunks(**retrieval_args)
    prerequisite_context = None
    if linked_ids and relationship is None and names is None:
        prerequisite_context, prerequisite_chunks, targets = await retrieve_prerequisite_context(
            linked_ids, authorized_course_ids, conversation.get("known_concepts_by_course"),
            conversation.get("course_settings_by_course"))
        chunks = merge_prerequisite_chunks(chunks, prerequisite_chunks, settings.CHAT_MAX_CHUNKS)
        if targets:
            yield format_sse("concepts", targets)
    if relationship is not None:
        relationship_context, evidence = await retrieve_relationship(relationship, authorized_course_ids)
        chunks = _prefer_relationship_evidence(chunks, evidence)
    if not chunks:
        logger.info("Chat retrieval stopped: no chunks linked_concepts=%d", len(linked_ids or []))
        if relationship_context and relationship_context.get("resolution") in {"ambiguous", "unresolved"}:
            reason = ("Tên các khái niệm khớp với nhiều cặp trong khóa học."
                      if relationship_context["resolution"] == "ambiguous"
                      else "Chưa xác định được đầy đủ hai khái niệm trong cùng khóa học.")
            answer = (f"{reason} Bạn hãy cung cấp tên cụ thể hơn hoặc mã khái niệm cho hai đầu quan hệ "
                      f'"{relationship_context["source"]}" → "{relationship_context["target"]}" nhé.')
            async for event in _path_events({"answer": answer, "chunks": []}):
                yield event
            return
        for event in await _fallback_events(course_id, query, concept_ids, authorized_course_ids, conversation):
            yield event
        return
    prompt_args = _prompt_arguments(course_id, query, chunks, configs, conversation, relationship_context, prerequisite_context)
    prompt = build_explain_prompt(message, chunks, **prompt_args)
    async for event in _answer_events(prompt, chunks):
        yield event


async def _path_discovery_events(course_id, query, concept_ids, authorized_course_ids, path_intent, public_courses, configs):
    if course_id is None or not public_courses:
        return []
    recommendations, missing = await discover_courses(
        authorized_course_ids, public_courses, query, concept_ids, None, path_intent.target, None)
    if not recommendations and not missing:
        return []
    # Missing graph annotations are not proof of missing course material.
    arguments = _retrieval_arguments(course_id, query, concept_ids, authorized_course_ids, configs)
    if await retrieve_chunks(**arguments):
        return []
    return _suggestion_events(recommendations) if recommendations else no_data_events()


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
    course_settings_by_course=None,
    public_courses=None,
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
        semantic = None
        if graph_relationship is None and (path_intent is None or needs_topic_context(path_intent)):
            yield ": understanding question\n\n"
            try:
                semantic = await asyncio.wait_for(classify_chat_intent(message, history, reply_context),
                                                  timeout=settings.CHAT_INTENT_TIMEOUT_SECONDS)
            except (ValueError, LLMUnavailableError, asyncio.TimeoutError) as error:
                logger.warning("Intent classification failed; using existing routing. error_type=%s", type(error).__name__)
            if semantic is not None:
                if semantic.intent == "clarify" or semantic.confidence < .7 or (
                    semantic.intent in {"learning_path", "prerequisites"} and semantic.relationship is None
                    and not (semantic.target or "").strip()
                ):
                    async for event in _path_events({"answer": "Bạn muốn tìm lộ trình, kiến thức tiên quyết hay giải thích cho khái niệm nào? Hãy cho mình tên khái niệm cụ thể nhé.", "chunks": []}):
                        yield event
                    return
                search_query = semantic.query.strip()
                path_intent = (PathIntent(semantic.target.strip())
                               if semantic.intent in {"learning_path", "prerequisites"} and semantic.relationship is None else None)
        if semantic is None and _needs_conversation_context(history, reply_context, path_intent):
            yield ": resolving conversation context\n\n"
            search_query, resolution_event = await _resolve_search_query(message, history, reply_context)
            yield resolution_event
        if graph_relationship is None and semantic is None:
            path_intent = detect_path_intent(search_query) or path_intent
        if path_intent is not None:
            fallback = await _path_discovery_events(course_id, search_query, concept_ids, authorized_course_ids,
                                                    path_intent, public_courses, course_settings_by_course)
            if fallback:
                for event in fallback:
                    yield event
                return
            yield ": computing prerequisite learning path\n\n"
            path = await build_chat_path(path_intent, authorized_course_ids, concept_ids, course_names,
                                         known_concepts_by_course)
            async for event in _path_events(path):
                yield event
            return
        conversation = {"history": history, "reply_context": reply_context, "graph_relationship": graph_relationship,
                        "known_concepts_by_course": known_concepts_by_course,
                        "course_settings_by_course": course_settings_by_course,
                        "public_courses": public_courses,
                        "target": semantic.target if semantic else None,
                        "relationship_names": semantic.relationship if semantic else None}
        async for event in _explain_events(course_id, message, search_query, concept_ids,
                                           authorized_course_ids, course_names, conversation):
            yield event
    except Exception as error:
        yield format_sse("error", {"error": str(error)})
        yield format_sse("done", {})


def chat_endpoint(req: ChatRequest):
    # A course authorized by Java may not have a Neo4j graph yet. Discovery must
    # also work for an empty corpus, rather than returning a graph-level 404.

    return StreamingResponse(
        chat_explain_stream_generator(req.course_id, req.message, req.concept_ids, req.authorized_course_ids,
                                      [item.model_dump() for item in req.history],
                                      [item.model_dump() for item in req.reply_context], req.course_names, req.graph_relationship,
                                      req.known_concepts_by_course, req.course_settings_by_course, req.public_courses),
        media_type="text/event-stream"
    )

