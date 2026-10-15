with open('app/concept_extraction/pipeline.py', 'r', encoding='utf-8') as f: lines = f.readlines()
lines = lines[:201]

lines.extend([
    '            await update_heartbeat(doc_id)\n',
    '\n',
    '            # Cuối cùng, Ghi 1 transaction\n',
    '            print(f"[{doc_id}] Saving transaction: {len(chunks)} chunks, {len(merged_new)} new concepts, {len(mentions)} mentions.")\n',
    '            t_save = time.perf_counter()\n',
    '            record = await save_ingestion_transaction(\n',
    '                doc_id=doc_id,\n',
    '                course_id=course_id,\n',
    '                chunks=chunks,\n',
    '                concepts=merged_new,\n',
    '                mentions=mentions\n',
    '            )\n',
    '\n',
    '            print(f"[{doc_id}] Save DB time: {time.perf_counter() - t_save:.2f}s")\n',
    '            if record:\n',
    '                print(f"[{doc_id}] Saved: {len(chunks)} chunks, {len(merged_new)} concepts, {len(mentions)} mentions. Version: {record[\'version\']}")\n',
    '                print(f"[{doc_id}] Deleted orphans: unknown")\n',
    '\n',
    '            return {\n',
    '                "merge_candidates": merge_cands,\n',
    '                "stats": {\n',
    '                    "dropped_evidence": total_dropped_no_ev,\n',
    '                    "total_returned": total_returned\n',
    '                }\n',
    '            }\n'
])
with open('app/concept_extraction/pipeline.py', 'w', encoding='utf-8') as f: f.writelines(lines)
