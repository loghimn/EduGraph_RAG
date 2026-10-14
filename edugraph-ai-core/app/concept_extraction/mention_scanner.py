from typing import List, Dict, Any, Tuple
import re
from app.core.text_utils import find_verbatim_evidence_offset
from app.config import settings

def is_valid_alias_for_exact_match(alias: str) -> bool:
    alias_strip = alias.strip()
    if len(alias_strip) >= settings.ALIAS_SCAN_MIN_LEN:
        return True
    if len(alias_strip) >= settings.ACRONYM_MIN_LEN and alias_strip.isupper() and alias_strip.isalpha():
        return True
    return False

def scan_mentions(chunks: List[Dict[str, Any]], concepts: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    # Thu thập raw matches (grouping from regex)
    grouped_mentions = {}
    
    for chunk in chunks:
        chunk_id = chunk["id"]
        raw_text = chunk["text"]
        
        for concept in concepts:
            concept_key = concept["key"]
            valid_aliases = []
            if is_valid_alias_for_exact_match(concept.get("name") or concept.get("canonical", "")):
                valid_aliases.append(concept.get("name") or concept.get("canonical", ""))
            for alias in concept.get("aliases", []):
                if is_valid_alias_for_exact_match(alias):
                    valid_aliases.append(alias)
                    
            for alias in valid_aliases:
                alias_esc = re.escape(alias.strip())
                pattern = r'(?i)(?<!\w)' + alias_esc + r'(?!\w)'
                
                for match in re.finditer(pattern, raw_text):
                    pair = (chunk_id, concept_key)
                    if pair not in grouped_mentions:
                        grouped_mentions[pair] = {
                            "chunk_id": chunk_id,
                            "concept_key": concept_key,
                            "is_evidence": False,
                            "start": match.start(),
                            "end": match.end(),
                            "count": 0
                        }
                    grouped_mentions[pair]["count"] += 1

    return list(grouped_mentions.values())

def verify_and_map_evidence(chunk_texts: List[str], evidence: str, preferred_chunk_idx: int) -> Tuple[int, int, int]:
    if 0 <= preferred_chunk_idx < len(chunk_texts):
        raw_chunk = chunk_texts[preferred_chunk_idx]
        pos = find_verbatim_evidence_offset(raw_chunk, evidence)
        if pos:
            return (preferred_chunk_idx, pos[0], pos[1])
            
    for i, raw_chunk in enumerate(chunk_texts):
        if i == preferred_chunk_idx:
            continue
        pos = find_verbatim_evidence_offset(raw_chunk, evidence)
        if pos:
            return (i, pos[0], pos[1])
            
    return None
