"""Cypher statements for concept_lifecycle use cases."""

CHANGE_STATUS_QUERY_1 = """
            MATCH (course:Course {id: $course_id}), (n:Concept {course_id: $course_id, id: $concept_id})
            SET n.status = $status,
                course.version = coalesce(course.version, 0) + 1
        """

CHANGE_STATUS_QUERY_2 = """ RETURN n.id AS id, n.status AS status, course.version AS graph_version"""

ARCHIVED_CONCEPTS_QUERY_1 = """
            MATCH (n:Concept {course_id: $course_id, status: 'ARCHIVED'})
            RETURN n.id AS id, n.name AS name, n.archived_by AS archived_by,
                n.archived_at AS archived_at ORDER BY n.archived_at DESC, n.id
        """

DELETION_IMPACT_QUERY_1 = """
            MATCH (n:Concept {id: $concept_id, course_id: $course_id})
            MATCH (course:Course {id: $course_id})
            OPTIONAL MATCH (ch:Chunk {course_id: $course_id})-[:MENTIONS]->(n)
            WITH n, course, count(DISTINCT ch) AS source_chunks
            OPTIONAL MATCH (src:Concept {course_id: $course_id})-[incoming:PREREQUISITE_OF]->(n)
            WHERE coalesce(src.status, 'ACTIVE') = 'ACTIVE' AND coalesce(incoming.status, 'AUTO') <> 'REJECTED'
            WITH n, course, source_chunks, count(incoming) AS incoming_edges
            OPTIONAL MATCH (n)-[outgoing:PREREQUISITE_OF]->(tgt:Concept {course_id: $course_id})
            WHERE coalesce(tgt.status, 'ACTIVE') = 'ACTIVE' AND coalesce(outgoing.status, 'AUTO') <> 'REJECTED'
            RETURN n.id AS id, n.name AS name, coalesce(n.status, 'ACTIVE') AS status,
                source_chunks, incoming_edges, count(outgoing) AS outgoing_edges,
                coalesce(course.version, 0) AS graph_version
        """

CHANGE_STATUS_QUERY_3 = """
            MATCH (course:Course {id: $course_id})
            SET course.concept_write_lock = coalesce(course.concept_write_lock, 0) + 1
            WITH course
            MATCH (n:Concept {course_id: $course_id, id: $concept_id})
            RETURN coalesce(n.status, 'ACTIVE') AS status, coalesce(course.version, 0) AS version
        """

CHANGE_STATUS_QUERY_4 = """
                MATCH (src:Concept {course_id: $course_id})-[r:PREREQUISITE_OF]->(tgt:Concept {course_id: $course_id})
                WHERE (coalesce(src.status, 'ACTIVE') = 'ACTIVE' OR src.id = $concept_id)
                  AND (coalesce(tgt.status, 'ACTIVE') = 'ACTIVE' OR tgt.id = $concept_id)
                  AND coalesce(r.status, 'AUTO') <> 'REJECTED'
                RETURN src.id AS source, tgt.id AS target
            """
