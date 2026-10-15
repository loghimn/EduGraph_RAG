import asyncio
import sys
import httpx
import time

from app.config import settings

async def main():
    print("Seeding course & document via HTTP or direct script? Seed script ran already.")
    
    # We will upload the real_doc.pdf to the ingest endpoint
    # First, start the fast api server if it's not running, or we can just start it in background.
    # We'll just assume the server is running on localhost:8000
    
    url = "http://localhost:8000/ingest"
    headers = {"X-Internal-Key": settings.INTERNAL_API_KEY}
    
    payload = {
        "document_id": "demo-sql-doc-2",
        "course_id": "demo-sql",
        "user_id": "dev-user",
        "filename": "real_doc.pdf"
    }
    
    # But wait! We need to copy scratch/real_doc.pdf to data/raw/demo-sql-doc-2/real_doc.pdf so the worker can find it!
    import os
    import shutil
    os.makedirs('data/raw/demo-sql-doc-2', exist_ok=True)
    shutil.copy('scratch/real_doc.pdf', 'data/raw/demo-sql-doc-2/real_doc.pdf')
    
    print("Calling POST /ingest")
    async with httpx.AsyncClient() as client:
        try:
            resp = await client.post(url, json=payload, headers=headers)
            print("Response:", resp.status_code, resp.text)
        except Exception as e:
            print("Server not running?", e)
            return
            
    print("Waiting for document to finish processing...")
    
    from app.db.neo4j_client import neo4j_client
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            # Poll status
            for _ in range(60):
                rec = await session.run("MATCH (d:Document {id: 'demo-sql-doc-2'}) RETURN d.status AS st")
                doc = await rec.single()
                st = doc['st']
                print(f"Status: {st} (Error: {''})")
                if st == 'DONE' or st == 'FAILED':
                    break
                await asyncio.sleep(2)
                
            # Print stats
            res = await session.run("MATCH (c:Chunk {doc_id: 'demo-sql-doc-2'}) RETURN count(c) AS cnt")
            print("Chunks:", (await res.single())['cnt'])
            
            res = await session.run("MATCH (c:Concept {course_id: 'demo-sql'}) RETURN count(c) AS cnt")
            print("Concepts:", (await res.single())['cnt'])
            
            res = await session.run("MATCH ()-[r:MENTIONS]->() RETURN count(r) AS cnt")
            print("MENTIONS total:", (await res.single())['cnt'])
            
            res = await session.run("MATCH ()-[r:MENTIONS {is_evidence: true}]->() RETURN count(r) AS cnt")
            print("MENTIONS (is_evidence=true):", (await res.single())['cnt'])
            
            res = await session.run("MATCH (c:Course {id: 'demo-sql'}) RETURN c.version AS v")
            print("Course version:", (await res.single())['v'])
            
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
