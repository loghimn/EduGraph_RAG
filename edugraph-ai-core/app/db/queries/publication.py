SNAPSHOT = """
OPTIONAL MATCH (c:Course {id: $course_id})
RETURN coalesce(c.version, 0) AS version, c.owner_id AS owner_id
"""
RECEIPT = """
MATCH (d:Document {id: $doc_id, course_id: $course_id})
RETURN d.review_id AS review_id
"""
LOCK_COURSE = """
MERGE (c:Course {id: $course_id})
ON CREATE SET c.owner_id = $owner_id, c.version = 0
SET c.concept_write_lock = coalesce(c.concept_write_lock, 0) + 1
RETURN coalesce(c.version, 0) AS version, c.owner_id AS owner_id
"""
DOCUMENT = """
MERGE (d:Document {id: $doc_id, course_id: $course_id})
SET d.filename = $filename, d.status = 'DONE', d.review_id = $review_id,
    d.reviewed_by = $owner_id, d.reviewed_at = timestamp(), d.updated_at = timestamp(),
    d.error = null, d.progress = null, d.current_step = null
WITH d
MATCH (c:Course {id: $course_id})
MERGE (c)-[:HAS_DOCUMENT]->(d)
"""
EDGES = """
UNWIND $edges AS edge
MATCH (s:Concept {id: edge.source_id, course_id: $course_id})
MATCH (t:Concept {id: edge.target_id, course_id: $course_id})
MERGE (s)-[r:PREREQUISITE_OF]->(t)
ON CREATE SET r.status = 'CONFIRMED', r.score = edge.score,
    r.evidence = edge.evidence, r.source = edge.source, r.source_type = edge.source,
    r.evidence_chunk_id = edge.evidence_chunk_id, r.model = edge.model,
    r.prompt_version = edge.prompt_version, r.reviewed_by = $owner_id,
    r.reviewed_at = timestamp()
WITH r, edge
WHERE r.status = 'AUTO'
SET r.status = 'CONFIRMED', r.score = edge.score, r.evidence = edge.evidence,
    r.reviewed_by = $owner_id, r.reviewed_at = timestamp()
"""
CONCEPTS = """
UNWIND $concepts AS concept
MATCH (c:Concept {course_key: concept.course_key})
SET c.curated = true, c.reviewed_by = $owner_id, c.reviewed_at = timestamp()
"""
