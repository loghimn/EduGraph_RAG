"""Cypher statements for chat use cases."""

VERIFY_COURSE_EXISTS_QUERY_1 = """MATCH (c:Course {id: $course_id}) RETURN c.id AS id"""

PATH_EVIDENCE = """
    MATCH (ch:Chunk {course_id: $course_id}) WHERE ch.id IN $chunk_ids
    RETURN ch.id AS id, ch.doc_id AS doc_id, ch.page AS page, ch.index AS index,
           substring(ch.text, 0, 1200) AS text
"""

EXACT_RELATIONSHIP = """
    MATCH (s:Concept {id: $source})-[r:PREREQUISITE_OF]->(t:Concept {id: $target})
    WHERE s.course_id IN $courses AND t.course_id = s.course_id
      AND coalesce(s.status, 'ACTIVE') = 'ACTIVE' AND coalesce(t.status, 'ACTIVE') = 'ACTIVE'
      AND coalesce(r.status, 'AUTO') <> 'REJECTED'
    OPTIONAL MATCH (ch:Chunk {id: r.evidence_chunk_id})
    WHERE ch.course_id = s.course_id
    RETURN s.name AS source, t.name AS target, coalesce(r.status, 'AUTO') AS status,
           r.score AS score, r.evidence AS evidence, r.review_note AS note,
           ch.id AS id, ch.text AS text, ch.doc_id AS doc_id, ch.page AS page, ch.index AS index
"""
