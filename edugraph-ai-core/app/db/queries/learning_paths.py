"""Cypher statements for learning_paths use cases."""

CREATE_LEARNING_PATH_PLAN_QUERY_1 = """
    MATCH (c:Course {id: $course_id})
    OPTIONAL MATCH (d:Document {id: $doc_id, course_id: $course_id})
    OPTIONAL MATCH (proc_d:Document {course_id: $course_id, status: 'PROCESSING'})
    OPTIONAL MATCH (ch:Chunk {doc_id: $doc_id, course_id: $course_id})-[:MENTIONS]->(dc:Concept {course_id: $course_id})
    WHERE coalesce(dc.status, 'ACTIVE') = 'ACTIVE'
    RETURN c.id AS course_id,
           coalesce(c.version, 0) AS course_version,
           d.status AS doc_status,
           d.progress AS doc_progress,
           count(DISTINCT proc_d) AS pending_doc_count,
           collect(DISTINCT dc.id) AS doc_concept_ids
    """
