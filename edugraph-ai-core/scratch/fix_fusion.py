with open('app/prerequisite/fusion.py', 'r', encoding='utf-8') as f:
    text = f.read()
text = text.replace('"source_type": source,', '"source": source,\n            "llm_score": llm_score,\n            "rule_score": rule_score,\n            "evidence_chunk_id": item.get("evidence_chunk_id", ""),\n            "model": item.get("model", ""),\n            "prompt_version": item.get("prompt_version", ""),')
with open('app/prerequisite/fusion.py', 'w', encoding='utf-8') as f:
    f.write(text)
