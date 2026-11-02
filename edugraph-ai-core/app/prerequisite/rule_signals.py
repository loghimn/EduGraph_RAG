import re
from functools import lru_cache
from typing import Dict, Any, List

# Các mẫu Regex để bắt tín hiệu.
# Sử dụng (target) và (candidate) làm placeholders.
PATTERNS = [
    # Tiếng Anh
    r"(?i)\b(?:requires?|needs?|depends on)\b\s+{candidate}", # Target requires Candidate
    r"(?i)\bbefore (?:learning|understanding)\b\s+{target}\b.*(?:need|require)\s+{candidate}", # Before Target, need Candidate

    # Tiếng Việt
    r"(?i)\b(?:yêu cầu|cần biết|dựa trên)\b\s+{candidate}",
    r"(?i)\btrước khi (?:học|tìm hiểu|biết)\b\s+{target}\b.*(?:cần)\s+{candidate}",

    # Tiếng Việt không dấu
    r"(?i)\b(?:yeu cau|can biet|dua tren)\b\s+{candidate}",
    r"(?i)\btruoc khi (?:hoc|tim hieu|biet)\b\s+{target}\b.*(?:can)\s+{candidate}"
]

@lru_cache(maxsize=2048)
def compile_pattern(pattern: str, target_name: str, candidate_name: str) -> re.Pattern:
    """Compile và cache regex pattern — tránh compile lại hàng nghìn lần mỗi lần upload."""
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
    Đánh giá tín hiệu Heuristic cho một cặp (candidate -> target).
    Trả về điểm cộng để hybrid với LLM.
    """
    score = 0.0
    for text in target_chunks_text:
        for p in PATTERNS:
            regex = compile_pattern(p, target_name, candidate_name)
            if regex.search(text):
                score += 0.5  # Thưởng điểm cho signal
                break # Chỉ thưởng 1 lần cho mỗi cặp/text nếu match
    return min(score, 1.0)
