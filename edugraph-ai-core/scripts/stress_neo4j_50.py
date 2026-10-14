import asyncio
import sys
import os
import time

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.db.neo4j_client import neo4j_client
from app.db.repo import save_ingestion_transaction

async def main():
    await neo4j_client.connect()
    
    doc_id = "stress-doc"
    course_id = "__stress_test__"
    
    # Tạo 300 chunks
    chunks = []
    for i in range(50):
        chunks.append({
            "id": f"{doc_id}:{i}",
            "doc_id": doc_id,
            "course_id": course_id,
            "index": i,
            "page": 1,
            "heading_path": "H1",
            "char_start": i * 100,
            "char_end": (i + 1) * 100,
            "text": f"Chunk text {i}",
            "text_unaccent": f"chunk text {i}",
            "embedding": [0.1] * 1024,
            "embedding_model": "test_model"
        })
        
    concepts = []
    mentions = []
    # 100 concepts
    for i in range(20):
        c_id = f"concept-{i}"
        concepts.append({
            "id": c_id,
            "course_id": course_id,
            "key": c_id,
            "canonical": f"Concept {i}",
            "aliases": [f"C{i}"],
            "language": "en",
            "order": i,
            "embedding": [0.1] * 1024,
            "embedding_model": "test_model"
        })
        # Mỗi concept có 3 mentions
        for j in range(3):
            mentions.append({
                "chunk_id": f"{doc_id}:{i*3+j}",
                "concept_key": c_id,
                "is_evidence": True,
                "start": 0,
                "end": 10,
                "count": 1
            })
            
    try:
        print("Bắt đầu ghi 300 chunks, 100 concepts, 300 mentions trong 1 transaction...")
        t0 = time.perf_counter()
        res = await save_ingestion_transaction(doc_id, course_id, chunks, concepts, mentions)
        t_save = time.perf_counter() - t0
        print(f"Ghi DB mất {t_save:.2f}s")
        
        print("Đang đếm số lượng bản ghi...")
        async with neo4j_client.driver.session() as session:
            r1 = await session.run("MATCH (c:Chunk {doc_id: 'stress-doc'}) RETURN count(c) AS cnt")
            r2 = await session.run("MATCH (c:Concept {course_id: '__stress_test__'}) RETURN count(c) AS cnt")
            r3 = await session.run("MATCH (c:Chunk)-[m:MENTIONS]->() WHERE c.doc_id = 'stress-doc' RETURN count(m) AS cnt")
            
            c1 = (await r1.single())['cnt']
            c2 = (await r2.single())['cnt']
            c3 = (await r3.single())['cnt']
            print(f"Thực tế ghi được -> Chunks: {c1}, Concepts: {c2}, Mentions: {c3}")
            
    except Exception as e:
        print(f"Thất bại (Neo4j từ chối transaction?): {e}")
    finally:
        print("Đang dọn dẹp course __stress_test__...")
        async with neo4j_client.driver.session() as session:
            await session.run("MATCH (n) WHERE n.course_id = '__stress_test__' OR n.doc_id = 'stress-doc' DETACH DELETE n")
            r = await session.run("MATCH (n) WHERE n.course_id = '__stress_test__' OR n.doc_id = 'stress-doc' RETURN count(n) AS cnt")
            c = (await r.single())['cnt']
            print(f"Còn lại sau khi dọn: {c} nodes.")
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
