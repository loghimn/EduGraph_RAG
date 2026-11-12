"""Cypher statements for concept_creation use cases."""

CHECK_DUPLICATE_QUERY_1 = """
        MATCH (n:Concept {course_id: $course_id})
        RETURN n.id AS id, n.key AS key, n.name AS name, coalesce(n.aliases, []) AS aliases
    """

COMPUTE_DRAFT_QUERY_1 = """MATCH (d:ConceptDraft)
            WHERE d.expires_at < timestamp() AND d.published_id IS NULL DELETE d"""

COMPUTE_DRAFT_QUERY_2 = """
            CREATE (d:ConceptDraft {id: $id, course_id: $course_id, created_by: $actor,
                name: $name, aliases: $aliases, language: $language, key: $key,
                embedding: $embedding, embedding_model: 'BAAI/bge-m3', created_at: timestamp(),
                expires_at: timestamp() + 86400000})
        """

PUBLISH_DRAFT_QUERY_1 = """MERGE (c:Course {id: $course_id})
            ON CREATE SET c.owner_id = $actor, c.version = 0
            SET c.concept_write_lock = coalesce(c.concept_write_lock, 0) + 1"""

PUBLISH_DRAFT_QUERY_2 = """MATCH (d:ConceptDraft {id: $id, course_id: $course_id, created_by: $actor})
            RETURN properties(d) AS draft"""

PUBLISH_DRAFT_QUERY_3 = """RETURN timestamp() AS now"""

PUBLISH_DRAFT_QUERY_4 = """
            MATCH (course:Course {id: $course_id})
            OPTIONAL MATCH (existing:Concept {course_id: $course_id})
            WITH course, coalesce(max(existing.order), 0) + 1 AS next_order
            CREATE (n:Concept {id: $id, course_id: $course_id, course_key: $course_key,
                key: $key, name: $name, aliases: $aliases, language: $language,
                embedding: $embedding, embedding_model: $embedding_model, order: next_order,
                origin: 'MANUAL', status: 'ACTIVE', curated: true, review_status: 'CONFIRMED', created_by: $actor, created_at: timestamp()})
            SET course.version = coalesce(course.version, 0) + 1
            WITH n, course
            MATCH (d:ConceptDraft {id: $draft_id})
            SET d.published_id = n.id
            REMOVE d.embedding
            RETURN n.id AS id, n.name AS name, n.aliases AS aliases, n.language AS language,
                n.order AS order, n.key AS key, course.version AS graph_version
        """

PUBLISH_DRAFT_QUERY_5 = """MATCH (c:Course {id: $course_id}) RETURN c.version AS version"""
