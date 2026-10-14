with open('app/concept_extraction/pipeline.py', 'r', encoding='utf-8') as f: lines = f.readlines()
for i in range(len(lines)):
    if 'print(f"[{doc_id}] Saving transaction:' in lines[i]:
        lines[i] = '            ' + lines[i].lstrip()
    if 't_save = time.perf_counter()' in lines[i]:
        lines[i] = '            ' + lines[i].lstrip()
    if 'record = await save_ingestion_transaction(' in lines[i]:
        lines[i] = '            ' + lines[i].lstrip()
    if 'doc_id=doc_id,' in lines[i]:
        lines[i] = '                ' + lines[i].lstrip()
    if 'course_id=course_id,' in lines[i]:
        lines[i] = '                ' + lines[i].lstrip()
    if 'chunks=chunks,' in lines[i]:
        lines[i] = '                ' + lines[i].lstrip()
    if 'concepts=merged_new,' in lines[i]:
        lines[i] = '                ' + lines[i].lstrip()
    if 'mentions=mentions' in lines[i] and 'len(mentions)' not in lines[i]:
        lines[i] = '                ' + lines[i].lstrip()
    if ')' in lines[i] and 'mentions=mentions' in lines[i-1]:
        lines[i] = '            ' + lines[i].lstrip()
    if "print(f'[{doc_id}] Save DB time:" in lines[i]:
        lines[i] = '            ' + lines[i].lstrip()
    if 'if record:' in lines[i]:
        lines[i] = '            ' + lines[i].lstrip()
    if 'print(f"[{doc_id}] Saved:' in lines[i]:
        lines[i] = '                ' + lines[i].lstrip()
    if 'print(f"[{doc_id}] Deleted orphans:' in lines[i]:
        lines[i] = '                ' + lines[i].lstrip()
    if 'return {' in lines[i] and '"merge_candidates"' in lines[i+1]:
        lines[i] = '            ' + lines[i].lstrip()
    if '"merge_candidates": merge_cands,' in lines[i]:
        lines[i] = '                ' + lines[i].lstrip()
    if '"stats": {' in lines[i]:
        lines[i] = '                ' + lines[i].lstrip()
    if '"dropped_evidence": total_dropped_no_ev,' in lines[i]:
        lines[i] = '                    ' + lines[i].lstrip()
    if '"total_returned": total_returned' in lines[i]:
        lines[i] = '                    ' + lines[i].lstrip()
    if '}' in lines[i] and 'total_returned' in lines[i-1]:
        lines[i] = '                ' + lines[i].lstrip()
    if '}' in lines[i] and 'stats' in lines[i-2]:
        lines[i] = '            ' + lines[i].lstrip()
with open('app/concept_extraction/pipeline.py', 'w', encoding='utf-8') as f: f.writelines(lines)
