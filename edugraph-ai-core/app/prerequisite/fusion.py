from typing import Dict, Any, List
from app.config import settings

def fuse_scores(
    target_id: str,
    llm_results: List[Dict[str, Any]],
    rule_scores_dict: Dict[str, float]
) -> List[Dict[str, Any]]:
    """
    K\u1ebft h\u1ee3p \u0111i\u1ec3m t\u1eeb LLM v\u00e0 Rule.
    Ch\u1ec9 \u0111\u1ec1 xu\u1ea5t c\u00e1c c\u1ea1nh M\u00c0 LLM \u0111\u00e3 ch\u1ecdn (llm_score > 0).
    """
    final_edges = []
    
    for item in llm_results:
        cand_id = item["id"]
        llm_score = float(item.get("confidence", 0.5))
        rule_score = float(rule_scores_dict.get(cand_id, 0.0))
        
        # fuse
        score = settings.W_LLM * llm_score + settings.W_RULE * rule_score
        
        if score >= settings.EDGE_MIN_SCORE:
            if rule_score > 0:
                source = "HYBRID"
            else:
                source = "LLM"
                
            final_edges.append({
                "source_id": cand_id,  # Prerequisite
                "target_id": target_id,
                "score": score,
                "source": source,
            "llm_score": llm_score,
            "rule_score": rule_score,
            "evidence_chunk_id": item.get("evidence_chunk_id", ""),
            "model": item.get("model", ""),
            "prompt_version": item.get("prompt_version", ""),
                "evidence": item.get("evidence", "")
            })
            
    # S\u1eafp x\u1ebfp l\u1ea1i theo score gi\u1ea3m d\u1ea7n v\u00e0 c\u1eaft t\u1ed1i \u0111a PREREQ_MAX_PER_CONCEPT n\u1ebfu c\u1ea7n
    final_edges.sort(key=lambda x: x["score"], reverse=True)
    return final_edges[:settings.PREREQ_MAX_PER_CONCEPT]
