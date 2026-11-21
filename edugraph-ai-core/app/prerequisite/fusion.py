import math
from typing import Dict, Any, List
from app.config import settings


def _embedding_adjustment(base_score, candidate, embedding_scores):
    if embedding_scores is None:
        return base_score, None
    embedding = embedding_scores.get(candidate)
    if embedding is None or not math.isfinite(embedding) or embedding < settings.PREREQ_MIN_EMBEDDING_SCORE:
        return float("nan"), embedding
    weight = settings.W_LLM + settings.W_RULE + settings.W_EMBEDDING
    if weight <= 0:
        return float("nan"), embedding
    return (base_score + settings.W_EMBEDDING * max(0, embedding)) / weight, embedding


def fuse_scores(target_id: str, llm_results: List[Dict[str, Any]],
                rule_scores_dict: Dict[str, float], embedding_scores=None) -> List[Dict[str, Any]]:
    """Embedding similarity validates relevance; the LLM still decides prerequisite direction."""
    final_edges = []
    for item in llm_results:
        candidate = item["id"]
        llm_score = float(item.get("confidence", 0.5))
        rule_score = float(rule_scores_dict.get(candidate, 0.0))
        score = settings.W_LLM * llm_score + settings.W_RULE * rule_score
        score, embedding = _embedding_adjustment(score, candidate, embedding_scores)
        if not math.isfinite(score) or score < settings.EDGE_MIN_SCORE:
            continue
        final_edges.append({
            "source_id": candidate, "target_id": target_id, "score": score,
            "source": "HYBRID" if rule_score > 0 or embedding is not None else "LLM",
            "llm_score": llm_score, "rule_score": rule_score, "embedding_score": embedding,
            "evidence_chunk_id": item.get("evidence_chunk_id", ""),
            "model": item.get("model", ""), "prompt_version": item.get("prompt_version", ""),
            "evidence": item.get("evidence", ""),
        })
    final_edges.sort(key=lambda edge: edge["score"], reverse=True)
    return final_edges[:settings.PREREQ_MAX_PER_CONCEPT]
