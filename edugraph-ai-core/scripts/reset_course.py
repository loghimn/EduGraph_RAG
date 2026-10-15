"""
Xóa toàn bộ dữ liệu của MỘT course: Document, Chunk, Concept, MENTIONS, PREREQUISITE_OF.

    python scripts/reset_course.py <course_id> --yes [--force]

- Bắt buộc truyền course_id và --yes, không có giá trị mặc định.
- Từ chối khi còn Document PROCESSING, trừ khi có --force.
- Mọi lệnh xóa nằm trong MỘT transaction (execute_write).
- Node Course được GIỮ LẠI (owner_id, version) và version tăng 1 trong cùng transaction,
  để graph_cache nhận ra dữ liệu đã đổi.
"""
import argparse
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.db.neo4j_client import neo4j_client  # noqa: E402

COUNT_QUERIES = {
    "Course": "MATCH (c:Course {id: $cid}) RETURN count(c) AS n",
    "Document": "MATCH (d:Document {course_id: $cid}) RETURN count(d) AS n",
    "Chunk": "MATCH (ch:Chunk {course_id: $cid}) RETURN count(ch) AS n",
    "Concept": "MATCH (co:Concept {course_id: $cid}) RETURN count(co) AS n",
    "MENTIONS": "MATCH (:Chunk {course_id: $cid})-[m:MENTIONS]->() RETURN count(m) AS n",
    "PREREQUISITE_OF": "MATCH (:Concept {course_id: $cid})-[p:PREREQUISITE_OF]->() RETURN count(p) AS n",
}

# Thứ tự: xóa quan hệ có tên trước để đếm riêng từng loại, rồi DETACH DELETE node.
DELETE_STEPS = [
    ("MENTIONS", "rels", "MATCH (:Chunk {course_id: $cid})-[m:MENTIONS]->() DELETE m"),
    ("PREREQUISITE_OF", "rels", "MATCH (:Concept {course_id: $cid})-[p:PREREQUISITE_OF]->() DELETE p"),
    ("Chunk", "nodes", "MATCH (ch:Chunk {course_id: $cid}) DETACH DELETE ch"),
    ("Concept", "nodes", "MATCH (co:Concept {course_id: $cid}) DETACH DELETE co"),
    ("Document", "nodes", "MATCH (d:Document {course_id: $cid}) DETACH DELETE d"),
]

BUMP_VERSION = """
MATCH (c:Course {id: $cid})
SET c.version = coalesce(c.version, 0) + 1
RETURN c.version AS version
"""


async def count_all(session, cid):
    out = {}
    for label, q in COUNT_QUERIES.items():
        rec = await (await session.run(q, cid=cid)).single()
        out[label] = rec["n"]
    return out


async def processing_docs(session, cid):
    res = await session.run(
        "MATCH (d:Document {course_id: $cid, status: 'PROCESSING'}) RETURN d.id AS id", cid=cid
    )
    return [r["id"] for r in await res.data()]


async def _delete_tx(tx, cid):
    deleted = {}
    extra_rels = 0
    for label, kind, q in DELETE_STEPS:
        summary = await (await tx.run(q, cid=cid)).consume()
        c = summary.counters
        if kind == "rels":
            deleted[label] = c.relationships_deleted
        else:
            deleted[label] = c.nodes_deleted
            extra_rels += c.relationships_deleted  # quan hệ khác bị DETACH
    rec = await (await tx.run(BUMP_VERSION, cid=cid)).single()
    version = rec["version"] if rec else None
    return deleted, extra_rels, version


async def reset_course(cid: str, force: bool) -> int:
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            before = await count_all(session, cid)
            print(f"[before] course_id={cid!r}: {before}")

            data_total = sum(v for k, v in before.items() if k != "Course")
            if data_total == 0:
                print(f"nothing found for course_id={cid!r} (Course node: {before['Course']})")
                return 0

            busy = await processing_docs(session, cid)
            if busy and not force:
                print(f"REFUSED: Document đang PROCESSING: {busy}. Dùng --force nếu chắc chắn.")
                return 2

            deleted, extra_rels, version = await session.execute_write(_delete_tx, cid)
            print(f"[deleted] {deleted} | other relationships detached: {extra_rels}")
            print(f"[course] kept, version -> {version}")

            after = await count_all(session, cid)
            print(f"[after]  course_id={cid!r}: {after}")
            return 0
    finally:
        await neo4j_client.close()


def main():
    p = argparse.ArgumentParser(description="Reset dữ liệu của một course.")
    p.add_argument("course_id", type=str)
    p.add_argument("--yes", action="store_true", required=True, help="Xác nhận xóa.")
    p.add_argument("--force", action="store_true", help="Cho phép xóa khi còn Document PROCESSING.")
    args = p.parse_args()

    cid = args.course_id.strip()
    if not cid:
        print("REFUSED: course_id rỗng.")
        sys.exit(1)

    sys.exit(asyncio.run(reset_course(cid, args.force)))


if __name__ == "__main__":
    main()
