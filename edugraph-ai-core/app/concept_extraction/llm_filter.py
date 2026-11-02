import asyncio
import os
import json
import logging
from typing import List, Dict, Any, Tuple
from app.config import settings
from app.core.llm import llm_client, estimate_request_tokens
from app.core.rate_limiter import token_limiter
from app.core.cache import cache, generate_cache_key
from app.core.text_utils import normalize_text_unaccent
from app.concept_extraction.mention_scanner import verify_and_map_evidence

# Tải prompt
prompt_path = os.path.join(os.path.dirname(__file__), "..", "..", "prompts", "concept_filter.txt")
with open(os.path.abspath(prompt_path), "r", encoding="utf-8") as f:
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
logger = logging.getLogger(__name__)

# Leave room for the JSON formatting repair instruction appended by generate().
JSON_REPAIR_RESERVE_TOKENS = 128


def combine_group_results(results):
    concepts = []
    stats = {"total_returned": 0, "dropped_no_evidence": 0,
             "dropped_format": 0, "dropped_list": []}
    for group_concepts, group_stats in results:
        concepts.extend(group_concepts)
        for key in ("total_returned", "dropped_no_evidence", "dropped_format"):
            stats[key] += group_stats[key]
        stats["dropped_list"].extend(group_stats["dropped_list"])
    return concepts, stats

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
    merged_candidates = ", ".join(sorted(flat_candidates))
    
    # 2. Thay thế vào prompt
    prompt = PROMPT_TEMPLATE.replace("<<TEXT>>", merged_text).replace("<<CANDIDATES>>", merged_candidates)

    # Split before generate(): switching providers cannot fix a local TPM overflow.
    estimated = estimate_request_tokens(prompt, json_mode=True)
    if estimated + JSON_REPAIR_RESERVE_TOKENS > token_limiter.tpm_limit:
        logger.info("Splitting concept group %s: estimated=%s, limit=%s, chunks=%s",
                    group_idx, estimated, token_limiter.tpm_limit, len(chunks))
        if len(chunks) > 1:
            middle = len(chunks) // 2
            results = []
            for start, end in ((0, middle), (middle, len(chunks))):
                results.append(await process_chunk_group(
                    group_idx, chunks[start:end], candidates_group[start:end]))
            return combine_group_results(results)

        # A single long chunk can also overflow. Keep its identity and translate
        # evidence offsets back into the original text after processing each half.
        if len(chunks) == 1 and len(chunks[0]["text"]) > 1:
            text = chunks[0]["text"]
            # Split near a suitable text boundary so a concept whose name and
            # explanation straddle the middle still lands in one part. Keep a
            # small overlap between the two parts for context, and translate
            # evidence offsets using each part's actual start position.
            middle = len(text) // 2
            boundary = middle
            window = min(50, middle, len(text) - middle)
            for i in range(middle, max(0, middle - window) - 1, -1):
                if text[i].isspace():
                    boundary = i
                    break
            else:
                for i in range(middle, min(len(text), middle + window)):
                    if text[i].isspace():
                        boundary = i
                        break
                else:
                    boundary = middle
            overlap = min(50, len(text) // 4) if len(text) >= 4 else 0
            if overlap <= 0:
                overlap = 0
            first_end = max(1, min(len(text) - 1, boundary + overlap))
            second_start = max(1, min(len(text) - 1, boundary - overlap))
            if second_start >= first_end:
                second_start = max(1, first_end - 1)
            results = []
            for start, end in ((0, first_end), (second_start, len(text))):
                if end <= start:
                    continue
                part = dict(chunks[0], text=text[start:end])
                concepts, stats = await process_chunk_group(
                    group_idx, [part], candidates_group[:1])
                for concept in concepts:
                    concept["evidence_start"] += start
                    concept["evidence_end"] += start
                results.append((concepts, stats))
            return combine_group_results(results)

        raise ValueError(
            "Concept filter prompt cannot fit the token budget even after splitting. "
            "Reduce candidate/template size or LLM_JSON_MAX_OUTPUT_TOKENS."
        )
    
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
