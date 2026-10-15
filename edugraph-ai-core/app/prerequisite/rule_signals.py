import re
from typing import Dict, Any, List

# C\u00e1c m\u1eabu Regex \u0111\u1ec3 b\u1eaft t\u00edn hi\u1ec7u.
# S\u1eed d\u1ee5ng (target) v\u00e0 (candidate) l\u00e0m placeholders.
PATTERNS = [
    # Ti\u1ebfng Anh
    r"(?i)\b(?:requires?|needs?|depends on)\b\s+{candidate}", # Target requires Candidate
    r"(?i)\bbefore (?:learning|understanding)\b\s+{target}\b.*(?:need|require)\s+{candidate}", # Before Target, need Candidate
    
    # Ti\u1ebfng Vi\u1ec7t
    r"(?i)\b(?:y\u00eau c\u1ea7u|c\u1ea7n bi\u1ebft|d\u1ef1a tr\u00ean)\b\s+{candidate}",
    r"(?i)\btr\u01b0\u1edbc khi (?:h\u1ecdc|t\u00ecm hi\u1ec3u|bi\u1ebft)\b\s+{target}\b.*(?:c\u1ea7n)\s+{candidate}",
    
    # Ti\u1ebfng Vi\u1ec7t kh\u00f4ng d\u1ea5u
    r"(?i)\b(?:yeu cau|can biet|dua tren)\b\s+{candidate}",
    r"(?i)\btruoc khi (?:hoc|tim hieu|biet)\b\s+{target}\b.*(?:can)\s+{candidate}"
]

def compile_pattern(pattern: str, target_name: str, candidate_name: str) -> re.Pattern:
    # Escape special regex chars in names
    t_safe = re.escape(target_name)
    c_safe = re.escape(candidate_name)
    
    p = pattern.replace("{target}", t_safe).replace("{candidate}", c_safe)
    return re.compile(p)

def evaluate_rule_signals(
    target_name: str,
    candidate_name: str,
    target_chunks_text: List[str]
) -> float:
    """
    \u0110\u00e1nh gi\u00e1 t\u00edn hi\u1ec7u Heuristic cho m\u1ed9t c\u1eb7p (candidate -> target).
    Tr\u1ea3 v\u1ec1 \u0111i\u1ec3m c\u1ed9ng \u0111\u1ec3 hybrid v\u1edbi LLM.
    """
    score = 0.0
    for text in target_chunks_text:
        for p in PATTERNS:
            regex = compile_pattern(p, target_name, candidate_name)
            if regex.search(text):
                score += 0.5  # Th\u01b0\u1edfng \u0111i\u1ec3m cho signal
                break # Ch\u1ec9 th\u01b0\u1edfng 1 l\u1ea7n cho m\u1ed7i c\u1eb7p/text n\u1ebfu match
    return min(score, 1.0)
