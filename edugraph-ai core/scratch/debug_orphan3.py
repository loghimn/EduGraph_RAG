import asyncio
from app.db.neo4j_client import neo4j_client
from app.db.repo import save_ingestion_transaction

async def main():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as s:
        await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")
        
    chunks = [{'id': 'c1', 'doc_id': 'd1', 'course_id': '__test__', 'index': 0, 'page': 1, 'text': 'A', 'embedding': [0.1]*1024, 'embedding_model': 'test', 'heading_path': 'H1', 'char_start': 0, 'char_end': 1, 'text_unaccent': 'A'}]
    concepts = [{'course_key': '__test__|C1', 'id': 'C1', 'course_id': '__test__', 'key': 'C1', 'name': 'C1', 'canonical': 'C1', 'order': 1, 'aliases': [], 'language': 'vi', 'embedding': [0.1]*1024, 'embedding_model': 'test'}]
    mentions = [{'chunk_id': 'c1', 'concept_key': 'C1', 'start': 0, 'end': 1, 'is_evidence': True, 'count': 1}]
    
    await save_ingestion_transaction('d1', '__test__', chunks, concepts, mentions)
    
    chunks2 = [{'id': 'c2', 'doc_id': 'd1', 'course_id': '__test__', 'index': 0, 'page': 1, 'text': 'A', 'embedding': [0.1]*1024, 'embedding_model': 'test', 'heading_path': 'H1', 'char_start': 0, 'char_end': 1, 'text_unaccent': 'A'}]
    concepts2 = [{'course_key': '__test__|C2', 'id': 'C2', 'course_id': '__test__', 'key': 'C2', 'name': 'C2', 'canonical': 'C2', 'order': 1, 'aliases': [], 'language': 'vi', 'embedding': [0.1]*1024, 'embedding_model': 'test'}]
    mentions2 = [{'chunk_id': 'c2', 'concept_key': 'C2', 'start': 0, 'end': 1, 'is_evidence': True, 'count': 1}]
    
    await save_ingestion_transaction('d1', '__test__', chunks2, concepts2, mentions2)
    
    async with neo4j_client.driver.session() as session:
        rec = await session.run("MATCH (c:Concept {course_id: '__test__'}) RETURN c.id AS id")
        ids = [r['id'] async for r in rec]
        print('Concepts:', ids)

asyncio.run(main())
