with open('app/concept_extraction/pipeline.py', 'r', encoding='utf-8') as f: text = f.read()
if '"mention_count":' not in text:
    text = text.replace(
        '"evidence_end": e.get("end", 0),',
        '"evidence_end": e.get("end", 0),\n            "evidence": e.get("evidence_text", ""),\n            "is_evidence": e.get("is_evidence", False),\n            "mention_count": len([m for m in valid_mentions if m["course_key"] == nc["course_key"]]),'
    )
with open('app/concept_extraction/pipeline.py', 'w', encoding='utf-8') as f: f.write(text)
