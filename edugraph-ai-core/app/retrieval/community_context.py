"""Bounded, versioned community summaries derived from authorized graph metadata."""
from collections import OrderedDict
from copy import deepcopy
import time
import asyncio
import networkx as nx
from app.config import settings
from app.retrieval.graph_cache import get_course_graph

_SUMMARIES = OrderedDict()
_MAX_COURSES = 64


def summarize_graph(graph):
    communities = nx.community.asyn_lpa_communities(graph.to_undirected(), seed=7)
    summaries = {}
    for members in communities:
        ordered = sorted(members)
        # Community context is an orientation aid, never a substitute for source passages.
        summary = {
            "concepts": [{"id": cid, "name": graph.nodes[cid].get("name", cid)} for cid in ordered[:15]],
            "prerequisites": [{"source": source, "target": target,
                               "status": graph[source][target].get("status", "AUTO")}
                              for source, target in sorted(graph.subgraph(members).edges())[:20]],
            "total_concepts": len(members),
        }
        for cid in members:
            summaries[cid] = summary
    return summaries


async def retrieve_community_context(concept_ids, authorized_courses, chunks):
    if not concept_ids:
        return []
    # Restrict to authorized courses that already supplied this turn's evidence.
    courses = sorted(set(authorized_courses) & {row.get("course_id") for row in chunks})
    result = []
    for course in courses:
        graph, version, _, _ = await get_course_graph(course)
        cached = _SUMMARIES.get(course)
        now = time.monotonic()
        ttl = settings.CHAT_SUMMARY_CACHE_TTL_SECONDS
        if cached is None or cached[0] != version or cached[1] <= now:
            summaries = await asyncio.to_thread(summarize_graph, graph)
            cached = (version, now + ttl, summaries)
            _SUMMARIES[course] = cached
        _SUMMARIES.move_to_end(course)
        while len(_SUMMARIES) > _MAX_COURSES:
            _SUMMARIES.popitem(last=False)
        seen = set()
        for cid in concept_ids:
            summary = cached[2].get(cid)
            if summary is None or id(summary) in seen:
                continue
            seen.add(id(summary))
            result.append({"course_id": course, "version": version, **deepcopy(summary)})
            if len(result) >= 3:
                return result
    return result
