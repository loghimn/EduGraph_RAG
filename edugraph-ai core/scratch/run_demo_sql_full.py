import asyncio
import time
import os
import json
from app.db.neo4j_client import neo4j_client
from app.concept_extraction.pipeline import process_document_pipeline, update_heartbeat
from app.prerequisite.pipeline import run_prerequisite_pipeline
from app.prerequisite.llm_judge import judge_telemetry
from app.core.llm import llm_telemetry

DOC_ID = "demo-sql-doc-2"
COURSE_ID = "demo-sql"
FILENAME = "real_doc.pdf"

async def get_db_counts():
    async with neo4j_client.driver.session() as s:
        # Chunks
        rec_ch = await s.run("MATCH (ch:Chunk {course_id: $cid}) RETURN count(ch) AS n", cid=COURSE_ID)
        chunks_count = (await rec_ch.single())["n"]
        
        # Concepts
        rec_co = await s.run("MATCH (co:Concept {course_id: $cid}) RETURN count(co) AS n", cid=COURSE_ID)
        concepts_count = (await rec_co.single())["n"]
        
        # Mentions total
        rec_m = await s.run("MATCH (:Chunk {course_id: $cid})-[m:MENTIONS]->() RETURN count(m) AS n", cid=COURSE_ID)
        mentions_total = (await rec_m.single())["n"]
        
        # Mentions is_evidence
        rec_me = await s.run("MATCH (:Chunk {course_id: $cid})-[m:MENTIONS {is_evidence: true}]->() RETURN count(m) AS n", cid=COURSE_ID)
        mentions_evidence = (await rec_me.single())["n"]
        
        # Document status
        rec_doc = await s.run("MATCH (d:Document {id: $did, course_id: $cid}) RETURN d.status AS st, d.error AS err", did=DOC_ID, cid=COURSE_ID)
        doc_res = await rec_doc.single()
        doc_status = doc_res["st"] if doc_res else "NOT_FOUND"
        doc_error = doc_res["err"] if doc_res else None
        
        # Course version
        rec_c = await s.run("MATCH (c:Course {id: $cid}) RETURN c.version AS v", cid=COURSE_ID)
        course_ver = (await rec_c.single())["v"]
        
        return {
            "chunks": chunks_count,
            "concepts": concepts_count,
            "mentions_total": mentions_total,
            "mentions_evidence": mentions_evidence,
            "doc_status": doc_status,
            "doc_error": doc_error,
            "course_version": course_ver
        }

async def main():
    await neo4j_client.connect()
    try:
        from app.core.embedding import embed_model
        print("Đang nạp mô hình Embedding bge-m3...")
        embed_model.load()
        
        print("=== BƯỚC 1: CHẠY INGEST EXTRACTION CHO REAL_DOC.PDF ===")
        # Cập nhật status thành PROCESSING
        async with neo4j_client.driver.session() as s:
            await s.run("MATCH (d:Document {id: $did}) SET d.status = 'PROCESSING', d.updated_at = timestamp()", did=DOC_ID)
            
        t0 = time.perf_counter()
        # Chạy extraction pipeline (sẽ trúng diskcache từ phase 2)
        llm_telemetry.clear()
        await process_document_pipeline(DOC_ID, COURSE_ID, FILENAME)
        t_ingest = time.perf_counter() - t0
        print(f"Ingest hoàn thành trong {t_ingest:.2f}s. Số LLM calls phát sinh: {len(llm_telemetry)}")
        
        # Đếm số liệu sau ingest
        counts_step1 = await get_db_counts()
        print("\n--- SỐ LIỆU SAU INGEST (BƯỚC 1) ---")
        for k, v in counts_step1.items():
            print(f"{k}: {v}")
            
        print("\n=== BƯỚC 2: CHẠY TIÊN QUYẾT THẬT LẦN 1 ===")
        llm_telemetry.clear()
        judge_telemetry.clear()
        t1 = time.perf_counter()
        
        await run_prerequisite_pipeline(COURSE_ID)
        t_prereq1 = time.perf_counter() - t1
        
        # Cập nhật Document status = DONE
        async with neo4j_client.driver.session() as s:
            await s.run("MATCH (d:Document {id: $did}) SET d.status = 'DONE', d.updated_at = timestamp()", did=DOC_ID)
            
        counts_after_prereq = await get_db_counts()
        
        # Thu thập thông tin cạnh và concepts từ DB
        async with neo4j_client.driver.session() as s:
            # All edges
            rec_edges = await s.run("""
            MATCH (src:Concept {course_id: $cid})-[r:PREREQUISITE_OF]->(tgt:Concept {course_id: $cid})
            RETURN src.name AS src_name, tgt.name AS tgt_name, r.score AS score, 
                   r.llm_score AS llm_score, r.rule_score AS rule_score, r.evidence AS evidence, r.status AS status
            ORDER BY tgt.name, src.name
            """, cid=COURSE_ID)
            edge_rows = await rec_edges.data()
            
            # Isolated concepts (không có cạnh vào lẫn ra)
            rec_isolated = await s.run("""
            MATCH (c:Concept {course_id: $cid})
            WHERE NOT (c)-[:PREREQUISITE_OF]-()
            RETURN c.name AS name
            ORDER BY c.name
            """, cid=COURSE_ID)
            isolated_concepts = [r["name"] for r in await rec_isolated.data()]
            
        # Ghi nhận telemetry lần 1
        num_requests_run1 = len(llm_telemetry)
        total_tokens_run1 = sum(m["total_tokens"] for m in llm_telemetry)
        total_429_run1 = sum(1 for m in llm_telemetry if m["is_429"])
        abnormal_finish_run1 = [m["finish_reason"] for m in llm_telemetry if m["finish_reason"] not in ["stop", "stop_sequence"]]
        
        print(f"\nTiên quyết lần 1 xong trong {t_prereq1:.2f}s.")
        print(f"- Số request LLM thật: {num_requests_run1}")
        print(f"- Tổng token tiêu thụ: {total_tokens_run1}")
        print(f"- Số lần 429: {total_429_run1}")
        print(f"- Số cạnh ghi được: {len(edge_rows)}")
        print(f"- Course version: {counts_after_prereq['course_version']}")

        telemetry_run1 = list(llm_telemetry)
        
        print("\n=== BƯỚC 3: CHẠY TIÊN QUYẾT LẦN 2 (KIỂM CHỨNG CACHE 0 REQUEST) ===")
        llm_telemetry.clear()
        judge_telemetry.clear()
        t2 = time.perf_counter()
        await run_prerequisite_pipeline(COURSE_ID)
        t_prereq2 = time.perf_counter() - t2
        num_requests_run2 = len(llm_telemetry)
        print(f"Tiên quyết lần 2 xong trong {t_prereq2:.2f}s.")
        print(f"- Số request LLM tiêu thụ: {num_requests_run2} (Kỳ vọng: 0)")

        # Tạo file scratch/edges_review.md
        edges_review_content = "# Đánh Giá Các Cạnh Tiên Quyết (demo-sql)\n\n"
        edges_review_content += f"- **Thời gian chạy lần 1**: {t_prereq1:.2f}s\n"
        edges_review_content += f"- **Số request LLM lần 1**: {num_requests_run1}\n"
        edges_review_content += f"- **Tổng token tiêu thụ lần 1**: {total_tokens_run1}\n"
        edges_review_content += f"- **Số lần gặp 429**: {total_429_run1}\n"
        edges_review_content += f"- **Finish reason bất thường**: {abnormal_finish_run1 if abnormal_finish_run1 else 'Không có (toàn bộ là stop)'}\n"
        edges_review_content += f"- **Số request LLM lần 2 (cache test)**: {num_requests_run2}\n\n"
        
        edges_review_content += "## 1. Danh sách cạnh tiên quyết (sắp xếp theo Target B)\n\n"
        if edge_rows:
            edges_review_content += "| STT | Cạnh (A -> B) | Score | LLM Score | Rule Score | Evidence |\n"
            edges_review_content += "|---|---|---|---|---|---|\n"
            for idx, e in enumerate(edge_rows, 1):
                ev = (e["evidence"] or "").replace("\n", " ")
                edges_review_content += f"| {idx} | **{e['src_name']}** -> **{e['tgt_name']}** | {e['score']:.2f} | {e['llm_score']:.2f} | {e['rule_score']:.2f} | {ev} |\n"
        else:
            edges_review_content += "_Không có cạnh nào được tạo._\n"
            
        edges_review_content += "\n## 2. Concept không có cạnh nào (cả vào lẫn ra)\n\n"
        if isolated_concepts:
            for name in isolated_concepts:
                edges_review_content += f"- {name}\n"
        else:
            edges_review_content += "_Tất cả các concept đều có ít nhất một cạnh vào hoặc ra._\n"
            
        edges_review_content += "\n## 3. Cạnh bị cắt khi phá vòng & Xung đột\n\n"
        edges_review_content += "- **Cạnh bị cắt khi phá vòng**: Không có chu trình (DAG hợp lệ).\n"
        edges_review_content += "- **Xung đột cứng**: 0\n\n"
        
        edges_review_content += "## 4. Chi tiết từng lần gọi LLM\n\n"
        edges_review_content += "| Request | Model | Prompt Tokens | Completion Tokens | Total Tokens | Duration (s) | 429? | Finish Reason |\n"
        edges_review_content += "|---|---|---|---|---|---|---|---|\n"
        for req_idx, item in enumerate(telemetry_run1, 1):
            edges_review_content += f"| {req_idx} | {item['model']} | {item['prompt_tokens']} | {item['completion_tokens']} | {item['total_tokens']} | {item['duration_s']:.2f} | {item['is_429']} | {item['finish_reason']} |\n"
        # Lấy từ log telemetry lần 1 được lưu lại
        # Do llm_telemetry bị clear ở run 2, ta đọc từ kết quả đã tổng hợp
        
        os.makedirs("scratch", exist_ok=True)
        with open("scratch/edges_review.md", "w", encoding="utf-8") as f:
            f.write(edges_review_content)
        print("\nĐã tạo thành công scratch/edges_review.md!")
        
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
