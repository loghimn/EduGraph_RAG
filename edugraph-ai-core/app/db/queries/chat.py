"""Cypher statements for chat use cases."""

VERIFY_COURSE_EXISTS_QUERY_1 = """MATCH (c:Course {id: $course_id}) RETURN c.id AS id"""
