with open('app/db/repo.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace('await tx.run("MATCH (old_c:Chunk {doc_id: $doc_id}) DETACH DELETE old_c", doc_id=doc_id)', 
'''deleted_chunks = (await (await tx.run("MATCH (old_c:Chunk {doc_id: $doc_id}) DETACH DELETE old_c", doc_id=doc_id)).consume()).counters.nodes_deleted
        inserted_chunks = 0
        inserted_mentions = 0
        deleted_orphans = 0''')
text = text.replace('await tx.run("""\n            UNWIND $chunks AS ch', 
'''res = await tx.run("""\n            UNWIND $chunks AS ch''')
text = text.replace('""", chunks=param_chunks[i:i+batch_size])', 
'''""", chunks=param_chunks[i:i+batch_size])\n            inserted_chunks += (await res.consume()).counters.nodes_created''')
text = text.replace('await tx.run("""\n            UNWIND $mentions AS m', 
'''res = await tx.run("""\n            UNWIND $mentions AS m''')
text = text.replace('""", mentions=param_mentions[i:i+batch_size])', 
'''""", mentions=param_mentions[i:i+batch_size])\n            inserted_mentions += (await res.consume()).counters.relationships_created''')
text = text.replace('await tx.run("""\n        OPTIONAL MATCH (orphan:Concept',
'''res = await tx.run("""\n        OPTIONAL MATCH (orphan:Concept''')
text = text.replace('""", course_id=course_id)\n        \n        res = await tx.run("""\n        MERGE (course:Course',
'''""", course_id=course_id)\n        deleted_orphans = (await res.consume()).counters.nodes_deleted\n        \n        res = await tx.run("""\n        MERGE (course:Course''')
text = text.replace('return {"version": rec["version"] if rec else 1}',
'''return {"version": rec["version"] if rec else 1, "deleted_chunks": deleted_chunks, "inserted_chunks": inserted_chunks, "inserted_mentions": inserted_mentions, "deleted_orphans": deleted_orphans}''')
with open('app/db/repo.py', 'w', encoding='utf-8') as f: f.write(text)
