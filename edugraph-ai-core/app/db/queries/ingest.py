"""Cypher statements for ingest use cases."""

INGEST_DOCUMENT_QUERY_1 = """
    MERGE (c:Course {id: $course_id})
    ON CREATE SET c.owner_id = $user_id, c.version = 0
    SET c.version = coalesce(c.version, 0)
    RETURN c.owner_id AS owner_id
    """

INGEST_DOCUMENT_QUERY_2 = """
    MERGE (d:Document {id: $doc_id, course_id: $course_id})
    ON CREATE SET d.status = 'UPLOADED'
    SET d.updated_at = timestamp()
    RETURN d.status AS status, properties(d)['run_id'] AS run_id
    """

RECOVER_PROCESSING_DOCUMENTS_QUERY_1 = """
    MATCH (d:Document {status: 'PROCESSING'})
    SET d.status = 'FAILED', d.error = 'interrupted by restart', d.updated_at = timestamp()
    """

GENERATE_COURSE_PREREQUISITES_QUERY_1 = """MATCH (c:Course {id: $course_id}) RETURN c.owner_id AS owner_id"""

GET_DOCUMENT_STATUS_QUERY_1 = """
    MATCH (d:Document {id: $doc_id, course_id: $course_id})
    RETURN d.status AS status,
           properties(d)['progress'] AS progress,
           properties(d)['error'] AS error,
           properties(d)['current_step'] AS current_step
    """

_BACKGROUND_INGEST_QUERY_1 = """
        MATCH (d:Document {id: $doc_id, course_id: $course_id})
        SET d.status = 'PROCESSING',
            d.error = null,
            d.current_step = 'EXTRACTING_TEXT_AND_CHUNKING',
            d.progress = null,
            d.updated_at = timestamp()
        """

_BACKGROUND_INGEST_QUERY_2 = """MATCH (d:Document {id: $doc_id, course_id: $course_id}) SET d.status = 'DONE', d.updated_at = timestamp()"""

_BACKGROUND_INGEST_QUERY_3 = """
        MATCH (d:Document {id: $doc_id, course_id: $course_id})
        SET d.status = 'FAILED', d.error = $error, d.updated_at = timestamp()
        """

VERIFY_DOCUMENT_OWNER_QUERY_1 = """MATCH (c:Course {id: $course_id}) RETURN c.owner_id AS owner_id"""

_BACKGROUND_INGEST_QUERY_4 = """
                MATCH (d:Document {id: $doc_id, course_id: $course_id})
                SET d.current_step = 'INFERRING_PREREQUISITES',
                    d.progress = null,
                    d.updated_at = timestamp()
                """

_BACKGROUND_INGEST_QUERY_5 = """MATCH (d:Document {id: $doc_id, course_id: $course_id}) SET d.pending_affected_concept_ids = null"""

INGEST_DOCUMENT_QUERY_3 = """MATCH (d:Document {id: $doc_id, course_id: $course_id}) SET d.status = 'PROCESSING', d.run_id = $run_id, d.error = null, d.updated_at = timestamp()"""

PAUSE_DOCUMENT_ENDPOINT_QUERY_1 = """MATCH (d:Document {id: $doc_id, course_id: $course_id}) RETURN d.status AS status"""

PAUSE_DOCUMENT_ENDPOINT_QUERY_2 = """MATCH (d:Document {id: $doc_id, course_id: $course_id}) SET d.status = 'PAUSED', d.error = null, d.updated_at = timestamp()"""

DELETE_DOCUMENT_ENDPOINT_QUERY_1 = """MATCH (d:Document {id: $doc_id, course_id: $course_id}) RETURN d.status AS status"""

DELETE_DOCUMENT_ENDPOINT_QUERY_2 = """MERGE (d:Document {id: $doc_id, course_id: $course_id}) SET d.status = 'CANCELLED', d.updated_at = timestamp()"""
