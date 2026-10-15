import uuid
from typing import List, Dict, Any, Tuple
from rapidfuzz import fuzz
import numpy as np
from app.config import settings
from app.core.text_utils import normalize_concept_key

def compute_cosine(vec1: List[float], vec2: List[float]) -> float:
    if not vec1 or not vec2:
        return 0.0
    v1 = np.array(vec1)
    v2 = np.array(vec2)
    norm1 = np.linalg.norm(v1)
    norm2 = np.linalg.norm(v2)
    if norm1 == 0 or norm2 == 0:
        return 0.0
    return float(np.dot(v1, v2) / (norm1 * norm2))

def is_subset(short_str: str, long_str: str) -> bool:
    return short_str.lower() in long_str.lower()

def is_acronym_of(acronym: str, full_name: str) -> bool:
    if len(acronym) < 2 or not acronym.isalpha() or not acronym.isupper():
        return False
    words = [w for w in full_name.split() if w.isalpha()]
    if len(words) != len(acronym):
        return False
    return all(w[0].upper() == a for w, a in zip(words, acronym))

def should_merge_plurals_or_head_nouns(c1: Dict[str, Any], c2: Dict[str, Any]) -> bool:
    if c1.get("language") != "en" and c2.get("language") != "en":
        return False
    key1 = c1.get("key", "").lower()
    key2 = c2.get("key", "").lower()
    
    words1 = key1.split()
    words2 = key2.split()
    
    # Plural rule
    if len(words1) == len(words2):
        for i in range(len(words1) - 1):
            if words1[i] != words2[i]:
                break
        else:
            w1 = words1[-1]
            w2 = words2[-1]
            exceptions = getattr(settings, "MERGE_ENGLISH_PLURALS_EXCEPTIONS", {"class", "analysis", "process", "series", "species", "status", "alias", "mass", "gas", "loss"})
            if w1 not in exceptions and w2 not in exceptions:
                if w1 + "s" == w2 or w2 + "s" == w1 or w1 + "es" == w2 or w2 + "es" == w1:
                    return True
                    
    # Generic head nouns rule
    heads = getattr(settings, "GENERIC_HEAD_NOUNS", {"command", "clause", "keyword", "statement"})
    if len(words1) > 0 and len(words2) > 0:
        if key1 + " " in [key2.replace(h, "") for h in heads if key2.endswith(" " + h)]:
            return True
        if key2 + " " in [key1.replace(h, "") for h in heads if key1.endswith(" " + h)]:
            return True
            
    return False

def should_merge_fuzzy(c1: Dict[str, Any], c2: Dict[str, Any]) -> bool:
    score = fuzz.token_sort_ratio(c1["canonical"], c2["canonical"])
    if score >= settings.FUZZY_THRESHOLD:
        if not is_subset(c1["canonical"], c2["canonical"]) and not is_subset(c2["canonical"], c1["canonical"]):
            return True
    return False

def should_merge_cosine(c1: Dict[str, Any], c2: Dict[str, Any]) -> bool:
    lang1 = c1.get("language", "vi")
    lang2 = c2.get("language", "vi")
    
    if lang1 == lang2 and lang1 != "mixed":
        if is_acronym_of(c1["canonical"], c2["canonical"]) or is_acronym_of(c2["canonical"], c1["canonical"]):
            return True
        return False
        
    cosine = compute_cosine(c1.get("embedding", []), c2.get("embedding", []))
    if cosine >= settings.MERGE_COSINE_MIN:
        if not is_subset(c1["canonical"], c2["canonical"]) and not is_subset(c2["canonical"], c1["canonical"]):
            return True
    return False

def check_alias_match(nc: Dict[str, Any], db_c: Dict[str, Any]) -> bool:
    nc_keys = {nc["key"]} | {normalize_concept_key(a) for a in nc.get("aliases", [])}
    db_keys = {db_c["key"]} | {normalize_concept_key(a) for a in db_c.get("aliases", [])}
    return len(nc_keys.intersection(db_keys)) > 0

def merge_concepts(new_concepts: List[Dict[str, Any]], db_concepts: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], List[Tuple[str, str, str]]]:
    merged_list = []
    merge_candidates = []
    # DB records từ get_course_concepts dùng key 'name', còn logic merger expects 'canonical'.
    # Chuẩn hóa tại entrypoint để tránh KeyError: 'canonical'.
    for c in db_concepts:
        if "canonical" not in c:
            c["canonical"] = c.get("name") or ""
    db_map = {c["key"]: c for c in db_concepts}
    
    # Sort new_concepts by length of canonical name so that shorter ones (singular/no head noun) are processed first
    # This ensures they become the canonical targets in db_map for subsequent longer variants.
    sorted_new = sorted(new_concepts, key=lambda c: len(c["canonical"]))
    
    for nc in sorted_new:
        key = normalize_concept_key(nc["canonical"])
        nc["key"] = key
        
        merged = False
        
        # 1. Alias Match
        for db_key, db_c in db_map.items():
            if check_alias_match(nc, db_c):
                if db_c.get("has_reviewed_edge", False):
                    merge_candidates.append((nc["canonical"], db_c["canonical"], "has_reviewed_edge (Alias Match)"))
                    nc["key"] = db_c["key"]   # remap mention -> concept đã duyệt
                    merged = True              # không vào merged_list -> không ghi đè properties
                else:
                    nc["merge_target_id"] = db_c["id"]
                    nc["key"] = db_c["key"]
                    merge_candidates.append((nc["canonical"], db_c["canonical"], "Merged (Alias Match)"))
                    merged = True
                break
                
        if merged: continue

        # 2. Plurals & Generic Heads
        for db_key, db_c in db_map.items():
            if should_merge_plurals_or_head_nouns(nc, db_c):
                if db_c.get("has_reviewed_edge", False):
                    merge_candidates.append((nc["canonical"], db_c["canonical"], "has_reviewed_edge (Plural/Head Noun)"))
                    nc["key"] = db_c["key"]   # remap mention -> concept đã duyệt
                    merged = True              # không vào merged_list -> không ghi đè properties
                else:
                    nc["merge_target_id"] = db_c["id"]
                    nc["key"] = db_c["key"]
                    merge_candidates.append((nc["canonical"], db_c["canonical"], "Merged (Plural/Head Noun)"))
                    merged = True
                break
                
        if merged: continue
        
        # 3. Fuzzy match
        for db_key, db_c in db_map.items():
            if should_merge_fuzzy(nc, db_c):
                if db_c.get("has_reviewed_edge", False):
                    merge_candidates.append((nc["canonical"], db_c["canonical"], "has_reviewed_edge (Fuzzy)"))
                    nc["key"] = db_c["key"]   # remap mention -> concept đã duyệt
                    merged = True              # không vào merged_list -> không ghi đè properties
                else:
                    nc["merge_target_id"] = db_c["id"]
                    nc["key"] = db_c["key"]
                    merge_candidates.append((nc["canonical"], db_c["canonical"], "Merged (Fuzzy)"))
                    merged = True
                break
                
        if merged: continue
            
        # 4. Cosine
        for db_key, db_c in db_map.items():
            if should_merge_cosine(nc, db_c):
                if db_c.get("has_reviewed_edge", False):
                    merge_candidates.append((nc["canonical"], db_c["canonical"], "has_reviewed_edge (Cosine)"))
                    nc["key"] = db_c["key"]   # remap mention -> concept đã duyệt
                    merged = True              # không vào merged_list -> không ghi đè properties
                else:
                    nc["merge_target_id"] = db_c["id"]
                    nc["key"] = db_c["key"]
                    merge_candidates.append((nc["canonical"], db_c["canonical"], "Merged (Cosine)"))
                    merged = True
                break
                
        if not merged:
            nc["id"] = str(uuid.uuid4())
            merged_list.append(nc)
            db_map[key] = nc 
            
    return merged_list, merge_candidates
