import asyncio
import os
import json
from typing import List, Dict, Any, Tuple
from app.config import settings
from app.core.llm import llm_client
from app.core.cache import cache, generate_cache_key
from app.core.text_utils import normalize_text_unaccent
from app.concept_extraction.mention_scanner import verify_and_map_evidence

# Tải prompt
with open(os.path.join(settings.RAW_DATA_DIR, "..", "..", "prompts", "concept_filter.txt"), "r", encoding="utf-8") as f:
    PROMPT_CONTENT = f.read()

# Tách PROMPT_VERSION và template
PROMPT_LINES = PROMPT_CONTENT.split('\n')
PROMPT_VERSION = "v1"
for line in PROMPT_LINES:
    if line.startswith("PROMPT_VERSION="):
        PROMPT_VERSION = line.split("=")[1].strip()
        break
        
PROMPT_TEMPLATE = "\n".join([line for line in PROMPT_LINES if not line.startswith("PROMPT_VERSION=")])

# Giới hạn số gọi LLM song song
semaphore = asyncio.Semaphore(settings.LLM_CONCURRENCY)

def determine_language(canonical: str, aliases: List[str], llm_lang: str) -> str:
    """Xác định mixed dựa vào dấu tiếng Việt hoặc heuristic."""
    # Nếu có dấu tiếng Việt -> vi
    def has_vietnamese(text):
        norm = normalize_text_unaccent(text)
        return norm != text
        
    canon_vi = has_vietnamese(canonical)
    
    # Heuristic đơn giản:
    has_vi = canon_vi
    has_en = not canon_vi and llm_lang == "en"
    
    for alias in aliases:
        if has_vietnamese(alias):
            has_vi = True
        else:
            has_en = True
            
    if has_vi and has_en:
        return "mixed"
    return llm_lang

async def process_chunk_group(group_idx: int, chunks: List[Dict[str, Any]], candidates_group: List[List[str]]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Gọi LLM lọc và thêm concept cho một nhóm chunk."""
    
    # 1. Chuẩn bị text
    text_blocks = []
    chunk_texts = []
    flat_candidates = set()
    
    for i, chunk in enumerate(chunks):
        text_blocks.append(f"[CHUNK {i+1}]\n{chunk['text']}")
        chunk_texts.append(chunk['text'])
        if i < len(candidates_group):
            flat_candidates.update(candidates_group[i])
            
    merged_text = "\n\n".join(text_blocks)
    merged_candidates = ", ".join(list(flat_candidates))
    
    # 2. Thay thế vào prompt
    prompt = PROMPT_TEMPLATE.replace("<<TEXT>>", merged_text).replace("<<CANDIDATES>>", merged_candidates)
    
    # 3. Cache key
    cache_data = {"text": merged_text, "candidates": sorted(list(flat_candidates))}
    cache_key = generate_cache_key(settings.MODEL_MAIN, PROMPT_VERSION, cache_data)
    
    cached_res = cache.get(cache_key)
    if cached_res:
        raw_json_dict = cached_res
    else:
        async with semaphore:
            raw_json_dict = await llm_client.generate(prompt, PROMPT_VERSION, cache_data, json_mode=True)
            if raw_json_dict:
                cache.set(cache_key, raw_json_dict)
                
    # 4. Phân tích kết quả
    valid_concepts = []
    stats = {"total_returned": 0, "dropped_no_evidence": 0, "dropped_format": 0, "dropped_list": []}
    
    if not raw_json_dict or "concepts" not in raw_json_dict:
        return valid_concepts, stats
        
    concepts_list = raw_json_dict.get("concepts", [])
    stats["total_returned"] = len(concepts_list)
    
    for c in concepts_list:
        try:
            canonical = c.get("canonical", "").strip()
            if not canonical:
                continue
                
            evidence = c.get("evidence", "").strip()
            chunk_num = c.get("chunk", 1) - 1 # 1-indexed to 0-indexed
            
            # Kiểm chứng evidence
            mapped = verify_and_map_evidence(chunk_texts, evidence, chunk_num)
            if not mapped:
                stats["dropped_no_evidence"] += 1
                stats["dropped_list"].append({"concept": c, "reason": "No evidence found in chunks"})
                continue
                
            actual_idx, start, end = mapped
            actual_chunk = chunks[actual_idx]
            
            aliases = c.get("aliases", [])
            lang = c.get("language", "vi")
            final_lang = determine_language(canonical, aliases, lang)
            
            valid_concepts.append({
                "canonical": canonical,
                "aliases": aliases,
                "language": final_lang,
                "evidence_chunk_id": actual_chunk["id"],
                "evidence_start": start,
                "evidence_end": end,
                "first_chunk_index": actual_chunk["index"]
            })
        except Exception:
            stats["dropped_format"] += 1
            stats["dropped_list"].append({"concept": c, "reason": "Format error or missing keys"})
            
    return valid_concepts, stats
