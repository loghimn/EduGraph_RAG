from typing import List, Dict, Any
from app.db.neo4j_client import neo4j_client

async def get_course_concepts(course_id: str) -> List[Dict[str, Any]]:
    """
    Nạp các concept hiện có của course, kèm cờ 'có cạnh đã duyệt' 
    để phục vụ gộp concept (luật M5).
    """
    query = """
    MATCH (c:Concept {course_id: $course_id})
    OPTIONAL MATCH (c)-[r:PREREQUISITE_OF]-()
    WITH c, collect(r.status) AS statuses
    RETURN 
        c.id AS id,
        c.key AS key,
        c.name AS name,
        c.aliases AS aliases,
        c.language AS language,
        c.embedding AS embedding,
        size([s IN statuses WHERE s IN ['CONFIRMED', 'MANUAL', 'REJECTED']]) > 0 AS has_reviewed_edge
    """
    async with neo4j_client.driver.session() as session:
        result = await session.run(query, course_id=course_id)
        records = await result.data()
        return records


async def get_knowledge_graph_concept(course_id: str, concept_id: str) -> Dict[str, Any] | None:
    """Load one concept and the document chunks that provide its source evidence."""
    query = """
    MATCH (c:Concept {course_id: $course_id, id: $concept_id})
    OPTIONAL MATCH (ch:Chunk {course_id: $course_id})-[:MENTIONS {is_evidence: true}]->(c)
    OPTIONAL MATCH (d:Document {id: ch.doc_id, course_id: $course_id})
    WITH c, ch, d
    ORDER BY coalesce(d.seq, 0), coalesce(ch.index, 0)
    WITH c, collect(DISTINCT CASE WHEN ch IS NULL THEN null ELSE {
        document_id: ch.doc_id,
        filename: d.filename,
        chunk_id: ch.id,
        page: ch.page,
        snippet: substring(coalesce(ch.text, ''), 0, 400)
    } END) AS sources
    RETURN c.id AS id,
           c.key AS key,
           c.name AS name,
           coalesce(c.aliases, []) AS aliases,
           coalesce(c.language, 'mixed') AS language,
           [source IN sources WHERE source IS NOT NULL][0..50] AS sources
    """
    async with neo4j_client.driver.session() as session:
        result = await session.run(query, course_id=course_id, concept_id=concept_id)
        record = await result.single()
        return record.data() if record else None


async def save_ingestion_transaction(
    doc_id: str,
    course_id: str,
    chunks: List[Dict[str, Any]],
    concepts: List[Dict[str, Any]],
    mentions: List[Dict[str, Any]]
):
    """
    Ghi MỘT transaction duy nhất bằng execute_write (idempotent).
    - Xóa chunk cũ của document (để re-ingest)
    - Ghi chunk mới
    - Ghi concept (MERGE theo course_key = course_id|key)
    - Ghi mentions mới
    - Dọn concept mồ côi (re-ingest) CHẠY SAU KHI GHI MENTIONS MỚI
    - Tăng version course.
    """
    # Dùng UNWIND cho danh sách
    query = """
    // 1. Xóa chunk cũ của tài liệu này (re-ingest an toàn)
    OPTIONAL MATCH (old_c:Chunk {doc_id: $doc_id})
    DETACH DELETE old_c
    WITH 1 AS _dummy
    
    // 2. Ghi Chunk mới
    UNWIND $chunks AS ch
    MERGE (c:Chunk {id: ch.id})
    SET c.doc_id = ch.doc_id,
        c.course_id = ch.course_id,
        c.index = ch.index,
        c.page = ch.page,
        c.heading_path = ch.heading_path,
        c.char_start = ch.char_start,
        c.char_end = ch.char_end,
        c.text = ch.text,
        c.text_unaccent = ch.text_unaccent,
        c.embedding = ch.embedding,
        c.embedding_model = ch.embedding_model
    WITH count(*) AS _dummy
    
    // 3. Ghi Concept mới (MERGE theo course_key)
    // Concept ID bất biến sau khi tạo. Dùng ON CREATE để gán UUID.
    UNWIND $concepts AS co
    MERGE (n:Concept {course_key: co.course_key})
    ON CREATE SET 
        n.id = co.id,
        n.course_id = co.course_id,
        n.key = co.key,
        n.order = co.order
    SET n.name = co.name,
        n.aliases = co.aliases,
        n.language = co.language,
        n.embedding = co.embedding,
        n.embedding_model = co.embedding_model
    WITH count(*) AS _dummy
    
    // 4. Ghi Mentions
    UNWIND $mentions AS m
    MATCH (chunk:Chunk {id: m.chunk_id})
    MATCH (concept:Concept {course_key: m.course_key})
    MERGE (chunk)-[r:MENTIONS {start: m.start, end: m.end}]->(concept)
    SET r.is_evidence = m.is_evidence
    WITH count(*) AS _dummy
    
    // 5. Dọn dẹp Concept mồ côi CỦA KHÓA HỌC (chạy sau khi ghi mentions mới)
    // Concept không có MENTIONS và không có PREREQUISITE duyệt.
    OPTIONAL MATCH (orphan:Concept {course_id: $course_id})
    WHERE coalesce(orphan.origin, 'AUTO') <> 'MANUAL'
    AND NOT (orphan)<-[:MENTIONS]-()
    AND NOT (orphan)-[:PREREQUISITE_OF {status: 'CONFIRMED'}]-()
    AND NOT (orphan)-[:PREREQUISITE_OF {status: 'MANUAL'}]-()
    // Xóa cả cạnh AUTO gắn với nó, rồi xóa nó
    DETACH DELETE orphan
    WITH count(*) AS _dummy
    
    // 6. Tăng version Course 1 lần
    MERGE (course:Course {id: $course_id})
    SET course.version = coalesce(course.version, 0) + 1
    
    RETURN course.version AS version
    """
    
    # Chuẩn bị parameters
    # Cần map concepts để có course_key, và truyền đúng dạng parameter
    param_chunks = []
    for c in chunks:
        param_chunks.append({
            "id": c["id"],
            "doc_id": c["doc_id"],
            "course_id": c["course_id"],
            "index": c["index"],
            "page": c["page"],
            "heading_path": c["heading_path"],
            "char_start": c["char_start"],
            "char_end": c["char_end"],
            "text": c["text"],
            "text_unaccent": c["text_unaccent"],
            "embedding": c.get("embedding", []),
            "embedding_model": c.get("embedding_model", "")
        })
        
    param_concepts = []
    for c in concepts:
        param_concepts.append({
            "id": c["id"],
            "course_id": course_id,
            "key": c["key"],
            "course_key": f"{course_id}|{c['key']}",
            "name": c["name"],
            "aliases": c["aliases"],
            "language": c["language"],
            "order": c["order"],
            "embedding": c.get("embedding", []),
            "embedding_model": c.get("embedding_model", "")
        })
        
    param_mentions = []
    for m in mentions:
        param_mentions.append({
            "chunk_id": m["chunk_id"],
            "course_key": f"{course_id}|{m['concept_key']}",
            "is_evidence": m["is_evidence"],
            "start": m["start"],
            "end": m["end"],
            "count": m.get("count", 1)
        })
        
    async def _tx_func(tx):
        # 1. Xóa chunk cũ
        deleted_chunks = (await (await tx.run("MATCH (old_c:Chunk {doc_id: $doc_id}) DETACH DELETE old_c", doc_id=doc_id)).consume()).counters.nodes_deleted
        inserted_chunks = 0
        inserted_mentions = 0
        deleted_orphans = 0
        
        from app.config import settings
        batch_size = getattr(settings, 'NEO4J_BATCH_SIZE', 100)
        
        # 2. Ghi chunk mới
        for i in range(0, max(1, len(param_chunks)), batch_size):
            if not param_chunks: break
            res = await tx.run("""
            UNWIND $chunks AS ch
            MERGE (c:Chunk {id: ch.id})
            SET c.doc_id = ch.doc_id,
                c.course_id = ch.course_id,
                c.index = ch.index,
                c.page = ch.page,
                c.heading_path = ch.heading_path,
                c.char_start = ch.char_start,
                c.char_end = ch.char_end,
                c.text = ch.text,
                c.text_unaccent = ch.text_unaccent,
                c.embedding = ch.embedding,
                c.embedding_model = ch.embedding_model
            """, chunks=param_chunks[i:i+batch_size])
            inserted_chunks += (await res.consume()).counters.nodes_created
            
        # 3. Ghi concept
        for i in range(0, max(1, len(param_concepts)), batch_size):
            if not param_concepts: break
            await tx.run("""
            UNWIND $concepts AS co
            MERGE (n:Concept {course_key: co.course_key})
            ON CREATE SET 
                n.id = co.id,
                n.course_id = co.course_id,
                n.key = co.key,
                n.order = co.order
            SET n.name = co.name,
                n.aliases = co.aliases,
                n.language = co.language,
                n.embedding = co.embedding,
                n.embedding_model = co.embedding_model
            """, concepts=param_concepts[i:i+batch_size])
            
        # 4. Ghi Mentions
        for i in range(0, max(1, len(param_mentions)), batch_size):
            if not param_mentions: break
            res = await tx.run("""
            UNWIND $mentions AS m
            MATCH (chunk:Chunk {id: m.chunk_id})
            MATCH (concept:Concept {course_key: m.course_key})
            MERGE (chunk)-[r:MENTIONS {start: m.start, end: m.end}]->(concept)
            SET r.is_evidence = m.is_evidence, r.count = m.count
            """, mentions=param_mentions[i:i+batch_size])
            inserted_mentions += (await res.consume()).counters.relationships_created
            
        # 5. Dọn dẹp Concept mồ côi
        res = await tx.run("""
        OPTIONAL MATCH (orphan:Concept {course_id: $course_id})
        WHERE coalesce(orphan.origin, 'AUTO') <> 'MANUAL'
        AND NOT (orphan)<-[:MENTIONS]-()
        AND NOT (orphan)-[:PREREQUISITE_OF {status: 'CONFIRMED'}]-()
        AND NOT (orphan)-[:PREREQUISITE_OF {status: 'MANUAL'}]-()
        AND NOT (orphan)-[:PREREQUISITE_OF {status: 'REJECTED'}]-()
        DETACH DELETE orphan
        """, course_id=course_id)
        deleted_orphans = (await res.consume()).counters.nodes_deleted
        
        res = await tx.run("""
        MERGE (course:Course {id: $course_id})
        SET course.version = coalesce(course.version, 0) + 1
        RETURN course.version AS version
        """, course_id=course_id)
        rec = await res.single()
        return {"version": rec["version"] if rec else 1, "deleted_chunks": deleted_chunks, "inserted_chunks": inserted_chunks, "inserted_mentions": inserted_mentions, "deleted_orphans": deleted_orphans}


    async with neo4j_client.driver.session() as session:
        record = await session.execute_write(_tx_func)
        return record


# --- RESTORED PHASE 1 SMOKE TEST FUNCS ---
async def smoke_test_ping():
    async with neo4j_client.driver.session() as session:
        result = await session.run("RETURN 1")
        return await result.single()

async def smoke_test_insert_vector(course_id: str, vector_list: list):
    async with neo4j_client.driver.session() as session:
        await session.run(
            "UNWIND $vectors AS v "
            "CREATE (c:Chunk {id: v.id, course_id: $course_id, embedding: v.emb, text_unaccent: 'test'})",
            course_id=course_id, vectors=vector_list
        )

async def smoke_test_vector_query(course_id: str, vector: list, k: int):
    query = """
    CALL db.index.vector.queryNodes('chunk_embedding', $k, $vector)
    YIELD node, score
    WHERE node.course_id = $course_id
    RETURN node.id AS id, score
    """
    async with neo4j_client.driver.session() as session:
        result = await session.run(query, k=k, vector=vector, course_id=course_id)
        return await result.data()

async def smoke_test_cleanup(course_id: str):
    async with neo4j_client.driver.session() as session:
        await session.run("MATCH (n {course_id: $course_id}) DETACH DELETE n", course_id=course_id)
