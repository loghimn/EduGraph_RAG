import pytest
import time
import json
import random
import networkx as nx
from unittest.mock import patch, AsyncMock
from fastapi.testclient import TestClient

from app.main import app
from app.retrieval.path_engine import generate_learning_path, validate_path
from app.retrieval.graph_cache import clear_course_graph_cache, GraphConflictError, get_course_graph

client = TestClient(app)

# Helper tạo fixture đồ thị mẫu
def create_sample_dag():
    G = nx.DiGraph()
    # Concept nodes: id, name, order
    G.add_node("C1", name="Basic Math", order=10)
    G.add_node("C2", name="Variables & Expressions", order=20)
    G.add_node("C3", name="Linear Equations", order=30)
    G.add_node("C4", name="Quadratic Equations", order=40)
    G.add_node("C5", name="Calculus", order=50)

    # Edges: C1 -> C2 -> C3 -> C4 -> C5
    G.add_edge("C1", "C2", status="AUTO", score=0.8)
    G.add_edge("C2", "C3", status="AUTO", score=0.85)
    G.add_edge("C3", "C4", status="AUTO", score=0.9)
    G.add_edge("C4", "C5", status="AUTO", score=0.95)
    return G

# 1. Test validate_path trên đồ thị gốc
def test_validate_path_valid_and_invalid():
    G = create_sample_dag()
    valid_order = ["C1", "C2", "C3", "C4", "C5"]
    assert validate_path(G, valid_order) == 0

    invalid_order = ["C5", "C4", "C3", "C2", "C1"]
    assert validate_path(G, invalid_order) > 0

# 2. Property test: Nhiều đồ thị DAG ngẫu nhiên -> validate_path luôn = 0
def test_property_validate_path_random_dags():
    random.seed(42)
    for _ in range(50):
        n_nodes = random.randint(5, 20)
        G = nx.DiGraph()
        nodes = [f"node_{i}" for i in range(n_nodes)]
        for i, n in enumerate(nodes):
            G.add_node(n, order=random.randint(1, 100), name=n)
            
        # Thêm cạnh xuôi chiều i < j để đảm bảo luôn là DAG
        for i in range(n_nodes):
            for j in range(i + 1, n_nodes):
                if random.random() < 0.3:
                    G.add_edge(nodes[i], nodes[j], status="AUTO", score=0.8)

        # Tính linear_order
        linear_order = list(nx.lexicographical_topological_sort(
            G, key=lambda c: (G.nodes[c].get("order", 0), G.nodes[c].get("name", ""), c)
        ))
        assert validate_path(G, linear_order) == 0

# 3. Test mode course (toàn bộ khóa học)
def test_mode_course():
    G = create_sample_dag()
    evidences = {}
    res = generate_learning_path(G=G, evidences=evidences, mode="course")
    assert res["mode"] == "course"
    assert len(res["linear_order"]) == 5
    for stage in res["stages"]:
        for step in stage["steps"]:
            assert step["outside_document"] is False

# 4. Test chế độ document với khóa học có HAI tài liệu có concept khác nhau
def test_mode_document_two_documents_isolation():
    # Giả lập đồ thị: Doc1 có {C1, C2}, Doc2 có {C3, C4, C5}
    # Cạnh: C1 -> C2 -> C3 -> C4 -> C5
    G = create_sample_dag()
    evidences = {
        "C1": {"chunk_id": "ch1", "page": 1, "snippet": "Text C1"},
        "C3": {"chunk_id": "ch3", "page": 2, "snippet": "Text C3"},
    }
    doc1_concepts = {"C1", "C2"}
    doc2_concepts = {"C3", "C4", "C5"}

    # 4a. Khi lọc theo Document 1 (không lấy external prereqs): chỉ ra {C1, C2}
    res_doc1 = generate_learning_path(
        G=G, evidences=evidences, mode="document",
        doc_concept_ids=doc1_concepts, include_external_prereqs=False
    )
    assert set(res_doc1["linear_order"]) == {"C1", "C2"}
    assert res_doc1["validation"]["violations"] == 0

    # 4b. Khi lọc theo Document 2 (không lấy external prereqs): chỉ ra {C3, C4, C5}
    res_doc2_no_ext = generate_learning_path(
        G=G, evidences=evidences, mode="document",
        doc_concept_ids=doc2_concepts, include_external_prereqs=False
    )
    assert set(res_doc2_no_ext["linear_order"]) == {"C3", "C4", "C5"}
    assert "C1" not in res_doc2_no_ext["linear_order"]
    assert "C2" not in res_doc2_no_ext["linear_order"]

    # 4c. Khi lọc theo Document 2 (kèm external prereqs): bổ sung C1, C2 và đánh dấu outside_document=True
    res_doc2_ext = generate_learning_path(
        G=G, evidences=evidences, mode="document",
        doc_concept_ids=doc2_concepts, include_external_prereqs=True
    )
    assert set(res_doc2_ext["linear_order"]) == {"C1", "C2", "C3", "C4", "C5"}
    outside_status = {step["concept_id"]: step["outside_document"] for stg in res_doc2_ext["stages"] for step in stg["steps"]}
    assert outside_status["C1"] is True
    assert outside_status["C2"] is True
    assert outside_status["C3"] is False
    assert outside_status["C4"] is False
    assert outside_status["C5"] is False

# 5. Test mode goal: assume_known_prerequisites (True vs False) & outside_document=None
def test_mode_goal_assume_known_prerequisites():
    G = create_sample_dag() # C1 -> C2 -> C3 -> C4 -> C5
    evidences = {}

    # Case 5a: assume_known_prerequisites = True (mặc định)
    # Mục tiêu C5, đã biết C3 -> C3 và tổ tiên của C3 (C1, C2) đều bị loại bỏ -> chỉ còn C4, C5
    res_assume_true = generate_learning_path(
        G=G, evidences=evidences, mode="goal",
        goal_concept_ids=["C5"], known_concept_ids=["C3"],
        assume_known_prerequisites=True
    )
    assert res_assume_true["linear_order"] == ["C4", "C5"]
    for stg in res_assume_true["stages"]:
        for step in stg["steps"]:
            assert step["outside_document"] is None

    # Case 5b: assume_known_prerequisites = False
    # Mục tiêu C5, đã biết C3 nhưng KHÔNG giả định tiên quyết của C3 đã biết
    # Tổ tiên của C5 là C1, C2, C3, C4. Loại trừ chỉ C3 -> Còn C1, C2, C4, C5!
    res_assume_false = generate_learning_path(
        G=G, evidences=evidences, mode="goal",
        goal_concept_ids=["C5"], known_concept_ids=["C3"],
        assume_known_prerequisites=False
    )
    assert set(res_assume_false["linear_order"]) == {"C1", "C2", "C4", "C5"}
    assert "C3" not in res_assume_false["linear_order"]

# 6. Test goal nằm trong known -> Lộ trình rỗng kèm thông báo
def test_goal_already_in_known_empty_path():
    G = create_sample_dag()
    res = generate_learning_path(
        G=G, evidences={}, mode="goal",
        goal_concept_ids=["C2"], known_concept_ids=["C2"]
    )
    assert res["linear_order"] == []
    assert res["stages"] == []
    assert res["stats"]["concepts"] == 0
    assert any("đã được nắm vững" in w for w in res["warnings"])

# 7. Test chu trình trong subgraph (kể cả cạnh AUTO) -> Báo lỗi GraphConflictError
def test_subgraph_cycle_detection_conflict_error():
    G = nx.DiGraph()
    G.add_node("C1", name="C1", order=1)
    G.add_node("C2", name="C2", order=2)
    G.add_edge("C1", "C2", status="AUTO", score=0.8)
    G.add_edge("C2", "C1", status="AUTO", score=0.8)

    with pytest.raises(GraphConflictError) as exc_info:
        generate_learning_path(G=G, evidences={}, mode="course")
    assert len(exc_info.value.conflicts) > 0

# 8. Test API Auth & Ownership (403, 404) & Single Cypher Query mock
@patch("app.api.learning_paths.neo4j_client")
def test_api_auth_ownership_and_errors(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session

    # 8a. Sai Internal Key -> 403
    resp = client.post("/learning-paths/plan", headers={"X-Internal-Key": "wrong"}, json={"course_id": "c1", "user_id": "u1"})
    assert resp.status_code == 403

    # 8b. Course không tồn tại -> 404
    mock_rec = AsyncMock()
    mock_rec.single.return_value = None
    mock_session.run.return_value = mock_rec
    resp = client.post("/learning-paths/plan", headers={"X-Internal-Key": "test-internal-api-key"}, json={"course_id": "c_missing", "user_id": "u1"})
    assert resp.status_code == 404

    # 8c. Sai chủ sở hữu -> 403
    mock_rec_owner = AsyncMock()
    mock_rec_owner.single.return_value = {
        "owner_id": "owner_other",
        "course_version": 1,
        "doc_status": None,
        "doc_progress": None,
        "pending_doc_count": 0,
        "doc_concept_ids": []
    }
    mock_session.run.return_value = mock_rec_owner
    resp = client.post("/learning-paths/plan", headers={"X-Internal-Key": "test-internal-api-key"}, json={"course_id": "c1", "user_id": "u1"})
    assert resp.status_code == 403

# 9. Test API: Goal không tồn tại trong course -> 404 kèm ID
@patch("app.api.learning_paths.get_course_graph")
@patch("app.api.learning_paths.neo4j_client")
def test_api_goal_not_found_404(mock_neo4j, mock_get_graph):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    mock_rec = AsyncMock()
    mock_rec.single.return_value = {
        "owner_id": "u1",
        "course_version": 1,
        "doc_status": None,
        "doc_progress": None,
        "pending_doc_count": 0,
        "doc_concept_ids": []
    }
    mock_session.run.return_value = mock_rec

    G = create_sample_dag() # Có C1..C5
    mock_get_graph.return_value = (G, 1, {}, [])

    resp = client.post(
        "/learning-paths/plan",
        headers={"X-Internal-Key": "test-internal-api-key"},
        json={"course_id": "c1", "user_id": "u1", "goal_concept_ids": ["UNKNOWN_ID"]}
    )
    assert resp.status_code == 404
    assert "UNKNOWN_ID" in resp.json()["detail"]

# 10. Test API: Có tài liệu PROCESSING -> warning pending_documents
@patch("app.api.learning_paths.get_course_graph")
@patch("app.api.learning_paths.neo4j_client")
def test_api_pending_documents_warning(mock_neo4j, mock_get_graph):
    clear_course_graph_cache()
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    mock_rec = AsyncMock()
    mock_rec.single.return_value = {
        "owner_id": "u1",
        "course_version": 1,
        "doc_status": None,
        "doc_progress": None,
        "pending_doc_count": 2,  # 2 tài liệu đang xử lý
        "doc_concept_ids": []
    }
    mock_session.run.return_value = mock_rec

    G = create_sample_dag()
    mock_get_graph.return_value = (G, 1, {}, [])

    resp = client.post(
        "/learning-paths/plan",
        headers={"X-Internal-Key": "test-internal-api-key"},
        json={"course_id": "c1", "user_id": "u1"}
    )
    assert resp.status_code == 200
    data = resp.json()
    assert any("pending_documents" in w for w in data["warnings"])

# 11. Test Cảnh báo thưa: Lộ trình phẳng (1 tầng, >1 concept) & tỉ lệ concept cô lập
def test_warnings_flat_path_and_isolated():
    # 11a: Lộ trình phẳng (2 concept không nối nhau -> 1 tầng)
    G = nx.DiGraph()
    G.add_node("C1", name="C1", order=1)
    G.add_node("C2", name="C2", order=2)
    res = generate_learning_path(G=G, evidences={}, mode="course")
    assert any("Lộ trình phẳng" in w for w in res["warnings"])
    assert any("Tỉ lệ concept cô lập" in w for w in res["warnings"])

# 12. Test cytoscape_data không chứa 'embedding' và chỉ chứa các trường yêu cầu
def test_cytoscape_data_no_embedding_and_format():
    G = create_sample_dag()
    # Thêm trường embedding giả lập vào node để kiểm tra xem có bị leak không
    for n in G.nodes:
        G.nodes[n]["embedding"] = [0.1, 0.2, 0.3]
        G.nodes[n]["aliases"] = ["alias1", "alias2"]

    res = generate_learning_path(G=G, evidences={}, mode="course")
    cyto_json = json.dumps(res["graph"])
    assert "embedding" not in cyto_json
    assert "aliases" not in cyto_json

    # Kiểm tra các trường dữ liệu bắt buộc của node
    for node_item in res["graph"]["elements"]["nodes"]:
        data = node_item["data"]
        assert set(data.keys()) == {"id", "name", "order", "role", "outside_document"}

# 13. Test Cache Invalidation khi Course.version thay đổi
@pytest.mark.asyncio
async def test_cache_invalidation_on_version_change():
    clear_course_graph_cache()
    
    with patch("app.retrieval.graph_cache.load_graph_from_neo4j") as mock_load:
        G1 = nx.DiGraph()
        G1.add_node("C1", name="C1", order=1)
        mock_load.return_value = (G1, {}, [])

        # Lần 1: version 1
        g_res, v_res, _, _ = await get_course_graph("c1", current_version=1)
        assert v_res == 1
        assert "C1" in g_res
        assert mock_load.call_count == 1

        # Lần 2: version vẫn là 1 -> dùng cache, không gọi lại DB
        g_res2, v_res2, _, _ = await get_course_graph("c1", current_version=1)
        assert mock_load.call_count == 1

        # Lần 3: version tăng lên 2 -> nạp lại từ DB
        G2 = nx.DiGraph()
        G2.add_node("C1", name="C1", order=1)
        G2.add_node("C2", name="C2", order=2)
        mock_load.return_value = (G2, {}, [])

        g_res3, v_res3, _, _ = await get_course_graph("c1", current_version=2)
        assert v_res3 == 2
        assert "C2" in g_res3
        assert mock_load.call_count == 2

# 14. Benchmark đồ thị 500 node: đo thời gian và kích thước payload
def test_benchmark_500_nodes():
    random.seed(123)
    N = 500
    G = nx.DiGraph()
    nodes = [f"concept_{i:04d}" for i in range(N)]
    for i, n in enumerate(nodes):
        G.add_node(n, name=f"Concept {i}", order=i * 10)
    
    # Tạo các cạnh xuôi chiều i -> j để đảm bảo DAG
    for i in range(N - 1):
        # Mỗi node kết nối tới 1-3 node tiếp theo
        for _ in range(random.randint(1, 3)):
            target_idx = random.randint(i + 1, min(i + 10, N - 1))
            G.add_edge(nodes[i], nodes[target_idx], status="AUTO", score=0.8)

    t0 = time.perf_counter()
    res = generate_learning_path(G=G, evidences={}, mode="course")
    duration = time.perf_counter() - t0

    payload_json = json.dumps(res, ensure_ascii=False)
    payload_size_bytes = len(payload_json.encode("utf-8"))
    payload_size_kb = payload_size_bytes / 1024.0

    print(f"\n[BENCHMARK 500 NODES] Duration: {duration*1000:.2f}ms | Payload Size: {payload_size_kb:.2f} KB ({payload_size_bytes} bytes)")
    
    # Đồ thị 500 nodes phải chạy dưới 500ms
    assert duration < 0.5
    # Kích thước payload nhỏ gọn (dưới 350KB)
    assert payload_size_kb < 350.0
    assert res["validation"]["violations"] == 0
    assert res["stats"]["concepts"] == 500
