with open('scripts/run_pipeline.py', 'r', encoding='utf-8') as f: text = f.read()
if '"merge_candidates":' not in text:
    text = text.replace(
        '"dropped": res.get("dropped_concepts", [])',
        '"dropped": res.get("dropped_concepts", []),\n                "merge_candidates": res.get("merge_candidates", [])'
    )
with open('scripts/run_pipeline.py', 'w', encoding='utf-8') as f: f.write(text)
