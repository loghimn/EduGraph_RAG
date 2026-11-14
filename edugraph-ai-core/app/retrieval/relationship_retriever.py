"""Retrieve the exact directed graph relationship and its authorized source passage."""
from app.db.neo4j_client import neo4j_client
from app.db.queries.chat import EXACT_RELATIONSHIP


async def retrieve_relationship(relationship, authorized_course_ids):
    if not authorized_course_ids:
        return None, []
    async with neo4j_client.driver.session() as session:
        result = await session.run(EXACT_RELATIONSHIP, source=relationship.source_id, target=relationship.target_id,
                                   courses=authorized_course_ids)
        record = await result.single()
    if not record:
        return {"recorded": False}, []
    row = dict(record)
    metadata = {key: row.get(key) for key in ["source", "target", "status", "score", "evidence", "note"]}
    metadata["recorded"] = True
    chunks = [{**row, "relationship_evidence": True}] if row.get("id") and row.get("text") else []
    return metadata, chunks
