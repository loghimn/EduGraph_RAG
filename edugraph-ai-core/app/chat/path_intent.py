"""Recognize requests for an ordered prerequisite path without a model call."""
import re
from dataclasses import dataclass

from app.core.text_utils import normalize_text_unaccent


@dataclass(frozen=True)
class PathIntent:
    target: str


def detect_path_intent(message: str) -> PathIntent | None:
    text = normalize_text_unaccent(message.casefold()).strip()
    if re.match(r"^(?:tai sao\b|vi sao\b|why\b|giai thich\b|explain\b|dinh nghia\b|define\b|what is\b)", text):
        return None
    if re.search(r"\b(?:khong|dung|do not|don't)\s+(?:tao|lap|sinh|create|generate)\b", text):
        return None
    if re.search(r"(?:lo trinh(?: hoc)?|learning path|roadmap|study plan)\s+(?:la gi|nghia la gi)\b", text):
        return None
    if re.search(r"\b(?:what is|explain|define)\s+(?:a |the )?(?:learning path|roadmap|study plan)\b", text):
        return None
    patterns = [
        r"(?:muon|de)\s+hoc\s+(.+?)\s+(?:(?:thi|toi|minh)\s+)?can\s+(?:biet|hoc|hieu|nam vung)\s+.*?\b(?:gi|truoc)\b",
        r"can\s+(?:hieu|biet|hoc|nam(?: vung)?)\s+.*?\btruoc\s+(?:khi\s+)?hoc\s+(.+)",
        r"(?:muon\s+)?hoc\s+(.+?)\s+(?:thi\s+)?can\s+(?:biet|hoc|nam(?: vung)?)\s+.*?\btruoc\b",
        r"^(.+?)\s+can\s+hoc\s+(?:nhung\s+)?gi\s+truoc\b",
        r"(?:dieu kien tien quyet|kien thuc tien quyet|tien quyet)\s+(?:cua|cho|de hoc)\s+(.+)",
        r"(?:what|which)\s+(?:should|do|must)\s+i\s+(?:know|learn|study|understand)\s+before\s+(?:learning|studying|mastering)?\s*(.+)",
        r"what\s+do\s+i\s+need\s+to\s+(?:know|learn|understand)\s+before\s+(?:learning|studying|mastering)?\s*(.+)",
        r"(?:prerequisites?\s+(?:for|of|to)|before learning)\s+(.+)",
        r"(?:lo trinh(?: hoc(?: tap)?)?|ke hoach hoc(?: tap)?)\s+(?:(?:de|cho|ve)\s+)?(?:(?:master|nam vung|thanh thao|hoc)\s+)?(.+)",
        r"(?:learning path|study plan|learning plan|roadmap)\s+(?:(?:for|to)\s+)?(?:(?:master|learn|mastering|learning)\s+)?(.+)",
    ]
    for pattern in patterns:
        match = re.search(pattern, text)
        if not match:
            continue
        target = re.split(r"[?!\n]", match.group(1))[0].strip(' .?!:;"“”‘’\'')
        target = re.sub(r"\s+(?:la gi|gom nhung gi|bao gom nhung gi|please|nhe|voi|a)$", "", target)
        # Knowledge is taken from the learner's saved mastery, not guessed from prose.
        target = re.split(r"[,;\n]|\s+(?:khi toi|toi da|i already|assuming i)\b", target)[0].strip()
        if target and target not in {"la gi", "gi", "what", "hoc", "tap"}:
            return PathIntent(target)
    return None


def needs_topic_context(intent: PathIntent) -> bool:
    return intent.target in {"no", "cai nay", "chu de nay", "khai niem nay", "it", "this", "that", "this topic"}
