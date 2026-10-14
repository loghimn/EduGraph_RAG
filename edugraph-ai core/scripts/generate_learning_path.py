import asyncio
import json
import argparse
from typing import Optional, List
from httpx import AsyncClient, ASGITransport

from app.main import app
from app.db.neo4j_client import neo4j_client

def print_learning_path(title: str, res: dict):
    print("\n" + "=" * 70)
    print(f"🎯 {title}")
    print("=" * 70)
    print(f"📌 Chế độ: {res.get('mode')} | Graph Version: {res.get('graph_version')}")
    print(f"📊 Thống kê: {res['stats']['concepts']} Concepts, {res['stats']['edges']} Edges, {res['stats']['isolated_concepts']} Isolated Concepts")
    
    if res.get("warnings"):
        print(f"⚠️ Cảnh báo: {', '.join(res['warnings'])}")
    else:
        print("✅ Cảnh báo: Không có")
        
    print(f"🛡️ Kiểm chứng topo (violations): {res['validation']['violations']} vi phạm")
    
    print("\n📚 THỨ TỰ TUYẾN TÍNH (LINEAR ORDER):")
    id_to_name = {}
    for stg in res.get("stages", []):
        for step in stg.get("steps", []):
            id_to_name[step["concept_id"]] = step["name"]
            
    order_names = [id_to_name.get(cid, cid) for cid in res.get("linear_order", [])]
    if order_names:
        print("   " + " ➔ ".join(order_names))
    else:
        print("   (Lộ trình rỗng - mục tiêu đã nắm vững)")
    
    print("\n🏫 CÁC TẦNG HỌC TẬP (STAGES):")
    for stg in res.get("stages", []):
        print(f"\n  🔹 [Tầng {stg['level']}]")
        for step in stg.get("steps", []):
            out_str = " (Ngoài tài liệu)" if step.get("outside_document") else ""
            prereqs = [id_to_name.get(p, p) for p in step.get("prerequisites_in_path", [])]
            prereq_str = f" [Cần trước: {', '.join(prereqs)}]" if prereqs else " [Không cần tiên quyết]"
            print(f"     • {step['name']}{out_str}{prereq_str}")
            if step.get("evidence"):
                ev = step["evidence"]
                snippet = ev.get("snippet", "").replace("\n", " ").strip()
                if len(snippet) > 100:
                    snippet = snippet[:100] + "..."
                print(f"       ↳ Trích dẫn (Trang {ev.get('page')}): \"{snippet}\"")

async def get_concept_map(course_id: str):
    async with neo4j_client.driver.session() as s:
        res = await s.run("MATCH (c:Concept {course_id: $cid}) RETURN c.id AS id, c.name AS name", cid=course_id)
        data = await res.data()
        name_to_id = {d["name"].strip().lower(): d["id"] for d in data}
        name_map = {d["name"].strip().lower(): d["name"] for d in data}
        return name_to_id, name_map

async def main():
    parser = argparse.ArgumentParser(description="Sinh lộ trình học từ Database Neo4j hiện tại")
    parser.add_argument("--course-id", default="demo-sql", help="Course ID (mặc định: demo-sql)")
    parser.add_argument("--user-id", default="dev-user", help="User ID chủ sở hữu (mặc định: dev-user)")
    parser.add_argument("--document-id", default="demo-sql-doc-2", help="Document ID (mặc định: demo-sql-doc-2)")
    parser.add_argument("--goal", help="Tên concept mục tiêu (ví dụ: view, INSTEAD OF INSERT)")
    parser.add_argument("--known", help="Tên concept đã biết, cách nhau bằng dấu phẩy (ví dụ: JOIN, primary key)")
    args = parser.parse_args()

    await neo4j_client.connect()
    try:
        name_to_id, name_map = await get_concept_map(args.course_id)

        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
            # 1. Nếu người dùng chỉ định mục tiêu cụ thể qua dòng lệnh
            if args.goal:
                goal_key = args.goal.strip().lower()
                if goal_key not in name_to_id:
                    print(f"❌ Không tìm thấy concept mục tiêu '{args.goal}' trong khóa học.")
                    print("Các concept khả dụng:", ", ".join(name_map.values()))
                    return

                goal_ids = [name_to_id[goal_key]]
                known_ids = []
                if args.known:
                    for k in args.known.split(","):
                        k_clean = k.strip().lower()
                        if k_clean in name_to_id:
                            known_ids.append(name_to_id[k_clean])

                payload = {
                    "course_id": args.course_id,
                    "user_id": args.user_id,
                    "goal_concept_ids": goal_ids,
                    "known_concept_ids": known_ids,
                    "include_external_prereqs": True
                }
                resp = await ac.post("/learning-paths/plan", headers={"X-Internal-Key": "correct"}, json=payload)
                if resp.status_code != 200:
                    print(f"❌ Lỗi {resp.status_code}: {resp.text}")
                    return

                data = resp.json()
                title = f"Lộ trình mục tiêu: {name_map.get(goal_key, args.goal)}"
                if known_ids:
                    known_names = [name_map.get(k.strip().lower(), k) for k in args.known.split(",") if k.strip().lower() in name_map]
                    title += f" (Đã biết: {', '.join(known_names)})"
                print_learning_path(title, data)

                with open("scratch/learning_path_result.json", "w", encoding="utf-8") as f:
                    json.dump(data, f, indent=2, ensure_ascii=False)
                print("\n💾 Đã lưu dữ liệu JSON và Cytoscape graph vào: scratch/learning_path_result.json")
                return

            # 2. Nếu chạy mặc định không truyền cờ, chạy bộ 3 kịch bản demo mẫu
            print("🚀 Đang khởi chạy sinh lộ trình học tập từ Database thực tế (demo-sql)...")

            # Kịch bản 1: Toàn bộ tài liệu demo-sql-doc-2
            payload1 = {
                "course_id": args.course_id,
                "user_id": args.user_id,
                "document_id": args.document_id,
                "include_external_prereqs": True
            }
            resp1 = await ac.post("/learning-paths/plan", headers={"X-Internal-Key": "correct"}, json=payload1)
            if resp1.status_code == 200:
                print_learning_path("KỊCH BẢN 1: LỘ TRÌNH TOÀN BỘ TÀI LIỆU (demo-sql-doc-2)", resp1.json())
            else:
                print(f"❌ Lỗi Kịch bản 1: {resp1.status_code} - {resp1.text}")

            # Kịch bản 2: Mục tiêu "view", đã biết "JOIN"
            if "view" in name_to_id and "join" in name_to_id:
                payload2 = {
                    "course_id": args.course_id,
                    "user_id": args.user_id,
                    "goal_concept_ids": [name_to_id["view"]],
                    "known_concept_ids": [name_to_id["join"]],
                    "include_external_prereqs": True
                }
                resp2 = await ac.post("/learning-paths/plan", headers={"X-Internal-Key": "correct"}, json=payload2)
                if resp2.status_code == 200:
                    print_learning_path("KỊCH BẢN 2: MỤC TIÊU 'view' | ĐÃ BIẾT 'JOIN'", resp2.json())

            # Kịch bản 3: Mục tiêu nâng cao "INSTEAD OF INSERT"
            if "instead of insert" in name_to_id:
                payload3 = {
                    "course_id": args.course_id,
                    "user_id": args.user_id,
                    "goal_concept_ids": [name_to_id["instead of insert"]],
                    "include_external_prereqs": True
                }
                resp3 = await ac.post("/learning-paths/plan", headers={"X-Internal-Key": "correct"}, json=payload3)
                if resp3.status_code == 200:
                    print_learning_path("KỊCH BẢN 3: MỤC TIÊU NÂNG CAO 'INSTEAD OF INSERT'", resp3.json())
                    with open("scratch/learning_path_result.json", "w", encoding="utf-8") as f:
                        json.dump(resp3.json(), f, indent=2, ensure_ascii=False)
                    print("\n💾 Đã lưu dữ liệu JSON và Cytoscape graph vào: scratch/learning_path_result.json")

    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
