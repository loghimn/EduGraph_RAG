"""Recognize requests for an ordered prerequisite path without a model call."""
import re
from dataclasses import dataclass

from app.core.text_utils import normalize_text_unaccent


@dataclass(frozen=True)
class PathIntent:
    target: str


def _remove_request_suffix(target: str) -> str:
    suffixes = ("bao gom nhung gi", "gom nhung gi", "nhu the nao", "the nao", "ra sao",
                "la gi", "please", "nhe", "voi", "a")
    while True:
        if target in {"bao gom nhung gi", "gom nhung gi", "nhu the nao", "the nao", "ra sao", "la gi"}:
            return ""
        for suffix in suffixes:
            prefix = target[:-len(suffix)] if target.endswith(suffix) else ""
            if prefix and prefix[-1].isspace():
                target = prefix.rstrip(' ,:;')
                break
        else:
            break
    return target


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
        # The goal can precede the path phrase: "học NULL thì lộ trình ra sao".
        # Match this before the generic path-first forms, which would take "ra sao".
        r"\bhoc\s+(.+?)\s+(?:thi\s+)?(?:lo trinh(?: hoc(?: tap)?)?|ke hoach hoc(?: tap)?)\s+(?:(?:se|la|nen)\s+)?(?:ra sao|nhu the nao|the nao)\b",
        # Use the earlier goal only when the path phrase has no explicit target.
        r"\bhoc\s+(.+?)\s*[,;]\s*(?:(?:hay\s+)?cho\s+(?:toi|tui|minh)\s+)?(?:mot\s+)?(?:lo trinh|ke hoach hoc)\b\s*(?:hoc(?: tap)?)?\s*(?:nhe|voi|a)?\s*[.!?]*$",
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
        target = _remove_request_suffix(target)
        # Knowledge is taken from the learner's saved mastery, not guessed from prose.
        target = re.split(r"[,;\n]|\s(?:khi toi|toi da|i already|assuming i)\b", target, maxsplit=1)[0].strip()
        if target not in {"khai niem nay", "chu de nay"}:
            target = re.sub(r"^(?:khai niem|chu de|concept|topic)\s+", "", target)
        target = target.strip(' .?!:;"“”‘’\'')
        if target and target not in {"la gi", "gi", "what", "hoc", "tap", "ra sao", "nhu the nao", "the nao"}:
            return PathIntent(target)
    return None


def needs_topic_context(intent: PathIntent) -> bool:
    return intent.target in {"no", "cai nay", "chu de nay", "khai niem nay", "it", "this", "that", "this topic"}
