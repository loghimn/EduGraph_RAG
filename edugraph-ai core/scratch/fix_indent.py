with open('app/concept_extraction/pipeline.py', 'r', encoding='utf-8') as f: lines = f.readlines()
for i in range(len(lines)):
    if 'completed_groups = 0' in lines[i]:
        lines[i] = '        completed_groups = 0\n'
    if 'total_groups =' in lines[i]:
        lines[i] = '        ' + lines[i].lstrip()
    if 'async def process_and_report' in lines[i]:
        lines[i] = '        async def process_and_report(idx, g_chunks, g_cands):\n'
    if 'nonlocal completed_groups' in lines[i]:
        lines[i] = '            nonlocal completed_groups\n'
    if 'res = await process_chunk_group' in lines[i]:
        lines[i] = '            res = await process_chunk_group(idx, g_chunks, g_cands)\n'
    if 'completed_groups +=' in lines[i]:
        lines[i] = '            completed_groups += 1\n'
    if 'await update_heartbeat(doc_id' in lines[i]:
        lines[i] = '            await update_heartbeat(doc_id, progress=f"{completed_groups}/{total_groups}")\n'
    if 'return res' in lines[i] and 'def process' not in lines[i-1]:
        lines[i] = '            return res\n'
    if 'tasks = []' in lines[i]:
        lines[i] = '        tasks = []\n'
    if 'chunk_groups = []' in lines[i]:
        lines[i] = '        chunk_groups = []\n'
    if 'for i in range(0, len(chunks), settings.CHUNKS_PER_LLM_CALL):' in lines[i]:
        lines[i] = '        for i in range(0, len(chunks), settings.CHUNKS_PER_LLM_CALL):\n'
    if 'group_chunks = chunks' in lines[i]:
        lines[i] = '            group_chunks = chunks[i:i+settings.CHUNKS_PER_LLM_CALL]\n'
    if 'group_cands = candidates' in lines[i]:
        lines[i] = '            group_cands = candidates[i:i+settings.CHUNKS_PER_LLM_CALL]\n'
    if 'chunk_groups.append(group_chunks)' in lines[i]:
        lines[i] = '            chunk_groups.append(group_chunks)\n'
    if 'tasks.append(process_and_report' in lines[i]:
        lines[i] = '            tasks.append(process_and_report(i, group_chunks, group_cands))\n'
with open('app/concept_extraction/pipeline.py', 'w', encoding='utf-8') as f: f.writelines(lines)
