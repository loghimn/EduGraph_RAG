import asyncio
import sys
import os
import time
import argparse

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.db.neo4j_client import neo4j_client
from app.concept_extraction.pipeline import process_document_pipeline
from app.db.repo import save_ingestion_transaction

async def delete_document(course_id: str, doc_id: str):
    print(f"[{doc_id}] Đang dọn dẹp Document và các chunk...")
    query = """
    MATCH (d:Document {id: $doc_id, course_id: $course_id})
    OPTIONAL MATCH (c:Chunk {doc_id: $doc_id})
    DETACH DELETE c
    DETACH DELETE d
    
    WITH count(c) AS _dummy
    OPTIONAL MATCH (orphan:Concept {course_id: $course_id})
    WHERE coalesce(orphan.origin, 'AUTO') <> 'MANUAL'
    AND NOT (orphan)<-[:MENTIONS]-()
    AND NOT (orphan)-[:PREREQUISITE_OF {status: 'CONFIRMED'}]-()
    AND NOT (orphan)-[:PREREQUISITE_OF {status: 'MANUAL'}]-()
    AND NOT (orphan)-[:PREREQUISITE_OF {status: 'REJECTED'}]-()
    DETACH DELETE orphan
    WITH count(*) AS _dummy
    MERGE (course:Course {id: $course_id})
    SET course.version = coalesce(course.version, 0) + 1
    """
    async with neo4j_client.driver.session() as session:
        await session.run(query, doc_id=doc_id, course_id=course_id)
        print(f"[{doc_id}] Dọn dẹp thành công.")

async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("filepath", help="Đường dẫn đến file cần ingest")
    parser.add_argument("--dry-run", action="store_true", help="Chạy pipeline nhưng không lưu vào Neo4j")
    parser.add_argument("--doc-id", default="doc-test-123")
    parser.add_argument("--course-id", default="__test__")
    parser.add_argument("--out", help="Đường dẫn file JSON xuất kết quả")
    args = parser.parse_args()
    
    filepath = args.filepath
    filename = os.path.basename(filepath)
    doc_id = args.doc_id
    course_id = args.course_id
    
    # Mock data setup
    from app.config import settings
    raw_dir = os.path.join(settings.RAW_DATA_DIR, doc_id)
    os.makedirs(raw_dir, exist_ok=True)
    import shutil
    shutil.copy(filepath, os.path.join(raw_dir, filename))
    
    import torch
    if torch.cuda.is_available():
        print(f"VRAM trước khi bắt đầu: {torch.cuda.memory_allocated() / 1e6:.2f} MB")
        
    print(f"Bắt đầu pipeline cho {filename} (course_id: {course_id})...")
    start_total = time.perf_counter()
    
    await neo4j_client.connect()
    from app.core.embedding import embed_model
    embed_model.load()
    
    try:
        if args.dry_run:
            print("[DRY-RUN] Sẽ không lưu vào DB.")
            from app.db import repo
            async def mock_save(*args, **kwargs):
                return {"version": 0}
            repo.save_ingestion_transaction = mock_save

        res = await process_document_pipeline(doc_id, course_id, filename)
        if res:
            stats = res["stats"]
            print("\n--- KẾT QUẢ ---")
            print(f"Tổng concept trả về: {stats['total_returned']}")
            print(f"Bị loại do evidence: {stats['dropped_evidence']}")
            
            cands = res.get("merge_candidates", [])
            print(f"Merge candidates ({len(cands)}): {cands}")
            
        if args.out and res:
            import json
            out_data = {
                "concepts": res.get("valid_concepts", []),
                "dropped": res.get("dropped_concepts", []),
                "merge_candidates": res.get("merge_candidates", [])
            }
            with open(args.out, "w", encoding="utf-8") as f:
                json.dump(out_data, f, ensure_ascii=False, indent=2)
            print(f"Đã xuất JSON ra {args.out}")
            
        if args.dry_run:
            async with neo4j_client.driver.session() as session:
                rec = await session.run("MATCH (n {course_id: '__test__'}) RETURN count(n) AS c")
                c = (await rec.single())["c"]
                print(f"[Xác minh DRY-RUN] Số node mang course_id __test__: {c}")

        if torch.cuda.is_available():
            print(f"VRAM đỉnh (Peak): {torch.cuda.max_memory_allocated() / 1e6:.2f} MB")
            print(f"VRAM kết thúc: {torch.cuda.memory_allocated() / 1e6:.2f} MB")
            
        if not args.dry_run and course_id == "__test__":
            await delete_document(course_id, doc_id)
            
    except Exception as e:
        print(f"Lỗi: {e}")
        import traceback
        traceback.print_exc()
    finally:
        await neo4j_client.close()
        
    print(f"Tổng thời gian: {time.perf_counter() - start_total:.2f}s")

if __name__ == "__main__":
    asyncio.run(main())
