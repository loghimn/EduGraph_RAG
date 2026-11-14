"""Cypher statements for graph_editor use cases."""

LOCK_GRAPH_QUERY_1 = """MATCH (c:Course {id: $course_id})
        SET c.concept_write_lock = coalesce(c.concept_write_lock, 0) + 1
        RETURN coalesce(c.version, 0) AS version"""

MUTATE_EDGE_QUERY_1 = """MATCH (s:Concept {course_id: $course_id, id: $source}), (t:Concept {course_id: $course_id, id: $target})
            MERGE (s)-[r:PREREQUISITE_OF]->(t)
            SET r.status = $status, r.review_note = $note, r.reviewed_by = $actor, r.reviewed_at = timestamp()
            """

UPDATE_CONCEPT_QUERY_1 = """MATCH (n:Concept {course_id: $course_id, id: $id})
            SET n.name = $name, n.aliases = $aliases, n.language = $language, n.embedding = $embedding,
                n.curated = true, n.review_status = 'CONFIRMED', n.reviewed_by = $actor, n.reviewed_at = timestamp()"""

MUTATE_EDGE_QUERY_2 = """MATCH (s:Concept {course_id: $course_id, id: $source})-[r:PREREQUISITE_OF]->(t:Concept {course_id: $course_id, id: $target})
                SET r.status = 'REJECTED', r.reviewed_by = $actor, r.reviewed_at = timestamp(), r.review_note = 'Replaced by instructor'"""

FINISH_QUERY_1 = """MATCH (c:Course {id: $course_id})
        SET c.version = coalesce(c.version, 0) + 1
        RETURN c.version AS graph_version"""

REVIEW_GRAPH_QUERY_1 = """MATCH (c:Course {id: $course_id}) RETURN coalesce(c.version, 0) AS version"""

REVIEW_GRAPH_QUERY_2 = """MATCH (n:Concept {course_id: $course_id})
            WHERE coalesce(n.status, 'ACTIVE') = 'ACTIVE'
            RETURN n.id AS id, n.name AS name, coalesce(n.aliases, []) AS aliases,
                coalesce(n.language, 'mixed') AS language, coalesce(n.review_status, 'AUTO') AS review_status,
                coalesce(n.origin, 'AUTO') AS origin ORDER BY name, id"""

REVIEW_GRAPH_QUERY_3 = """MATCH (s:Concept {course_id: $course_id})-[r:PREREQUISITE_OF]->(t:Concept {course_id: $course_id})
            WHERE coalesce(s.status, 'ACTIVE') = 'ACTIVE' AND coalesce(t.status, 'ACTIVE') = 'ACTIVE'
            RETURN s.id AS source, t.id AS target, coalesce(r.status, 'AUTO') AS status,
                coalesce(r.score, 0) AS score, coalesce(r.evidence, '') AS evidence,
                r.evidence_chunk_id AS evidence_chunk_id, coalesce(r.review_note, '') AS note,
                r.reviewed_by AS reviewed_by, r.reviewed_at AS reviewed_at
            ORDER BY source, target"""

REVIEW_GRAPH_QUERY_4 = """MATCH (c:Course {id: $course_id}) RETURN coalesce(c.version, 0) AS version"""

MUTATE_EDGE_QUERY_3 = """MATCH (n:Concept {course_id: $course_id})
            WHERE coalesce(n.status, 'ACTIVE') = 'ACTIVE' RETURN n.id AS id"""

MUTATE_EDGE_QUERY_4 = """MATCH (s:Concept {course_id: $course_id})-[r:PREREQUISITE_OF]->(t:Concept {course_id: $course_id})
            WHERE coalesce(s.status, 'ACTIVE') = 'ACTIVE' AND coalesce(t.status, 'ACTIVE') = 'ACTIVE'
            RETURN s.id AS source, t.id AS target, coalesce(r.status, 'AUTO') AS status"""

UPDATE_CONCEPT_QUERY_2 = """MATCH (n:Concept {course_id: $course_id})
            RETURN n.id AS id, n.key AS key, n.name AS name, coalesce(n.aliases, []) AS aliases,
                coalesce(n.status, 'ACTIVE') AS status"""

CONFIRM_CONCEPT_QUERY_1 = """MATCH (n:Concept {course_id: $course_id, id: $id})
            WHERE coalesce(n.status, 'ACTIVE') = 'ACTIVE'
            SET n.curated = true, n.review_status = 'CONFIRMED', n.reviewed_by = $actor, n.reviewed_at = timestamp()
            RETURN n.id AS id"""
