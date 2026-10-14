import os
import json
import asyncio
from typing import List, Dict, Any
from app.config import settings
from app.core.llm import llm_client
from app.core.cache import cache, generate_cache_key

# L\u00e0m t\u01b0\u01a1ng t\u1ef1 llm_filter.py \u0111\u1ec3 n\u1ea1p prompt
PROMPT_VERSION = "v1"
PROMPT_TEMPLATE = ""

prompt_path = os.path.join(settings.RAW_DATA_DIR, "..", "..", "prompts", "prerequisite.txt")
if os.path.exists(prompt_path):
    with open(prompt_path, "r", encoding="utf-8") as f:
        content = f.read()
        lines = content.split('\n')
        for line in lines:
            if line.startswith("PROMPT_VERSION="):
                PROMPT_VERSION = line.split("=")[1].strip()
                break
        PROMPT_TEMPLATE = "\n".join([line for line in lines if not line.startswith("PROMPT_VERSION=")])
else:
    # Dummy n\u1ebfu ch\u01b0a c\u00f3 file
    PROMPT_TEMPLATE = "TARGET: {target}\nCANDIDATES: {candidates}\nEVIDENCE: {evidence}"

judge_telemetry = []
semaphore = asyncio.Semaphore(settings.LLM_CONCURRENCY)

async def judge_prerequisites(
    target: Dict[str, Any],
    candidates: List[Dict[str, Any]],
    target_evidence_texts: List[str]
) -> List[Dict[str, Any]]:
    """
    G\u1ecdi LLM \u0111\u1ec3 \u0111\u00e1nh gi\u00e1 c\u00e1c candidates xem c\u00e1i n\u00e0o l\u00e0 ti\u00ean quy\u1ebft c\u1ee7a target.
    """
    if not candidates:
        return []
        
    # Chu\u1ea9n b\u1ecb context
    target_info = f"Name: {target['name']}"
    if target.get('aliases'):
        target_info += f" (Alias: {target['aliases'][0]})"
        
    cand_info = []
    for c in candidates:
        c_str = f"- {c['name']}"
        if c.get('aliases'):
            c_str += f" (Alias: {c['aliases'][0]})"
        cand_info.append(c_str)
        
    # Gi\u1edbi h\u1ea1n evidence v\u00e0 \u0111\u1ed9 d\u00e0i
    evidence_str = ""
    for idx, text in enumerate(target_evidence_texts[:settings.JUDGE_MAX_EVIDENCE_CHUNKS]):
        evidence_str += f"[Evidence {idx+1}]: {text[:settings.JUDGE_CHUNK_CHARS]}\n"
        
    prompt = PROMPT_TEMPLATE.replace("{target}", target_info)
    prompt = prompt.replace("{candidates}", "\n".join(cand_info))
    prompt = prompt.replace("{evidence}", evidence_str)
    
    cache_data = {
        "target": target["name"],
        "candidates": sorted([c["name"] for c in candidates]),
        "evidence": evidence_str
    }
    cache_key = generate_cache_key(settings.MODEL_MAIN, PROMPT_VERSION, cache_data)
    
    is_cached = False
    cached_res = cache.get(cache_key)
    if cached_res:
        raw_json_dict = cached_res
        is_cached = True
    else:
        async with semaphore:
            raw_json_dict = await llm_client.generate(prompt, PROMPT_VERSION, cache_data, json_mode=True)
            if raw_json_dict:
                cache.set(cache_key, raw_json_dict)
                
    if not raw_json_dict or "prerequisites" not in raw_json_dict:
        judge_telemetry.append({
            "target": target["name"],
            "candidates_count": len(candidates),
            "cached": is_cached,
            "prerequisites": []
        })
        return []
        
    # Validate against candidate list (LLM cannot hallucinate names)
    valid_cand_names = {c["name"].lower() for c in candidates}
    valid_results = []
    
    for item in raw_json_dict.get("prerequisites", [])[:settings.PREREQ_MAX_PER_CONCEPT]:
        name = item.get("name", "")
        if name.lower() in valid_cand_names:
            # Find the matching candidate to attach the ID
            cand_obj = next((c for c in candidates if c["name"].lower() == name.lower()), None)
            if cand_obj:
                valid_results.append({
                    "id": cand_obj["id"],
                    "name": cand_obj["name"],
                    "confidence": item.get("confidence", 0.5),
                    "evidence": item.get("evidence", "")
                })
                
    judge_telemetry.append({
        "target": target["name"],
        "candidates_count": len(candidates),
        "cached": is_cached,
        "prerequisites": [v["name"] for v in valid_results]
    })
    return valid_results
