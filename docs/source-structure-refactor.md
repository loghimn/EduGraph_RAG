# Chuẩn hóa cấu trúc source — 2026-11-12

## Các thay đổi

| Mục audit | Kết quả |
| --- | --- |
| J1 | ActivityAlertService là interface; triển khai, Clock và cửa sổ cảnh báo nằm trong ActivityAlertServiceImpl. |
| J2 | NotebookService là interface; triển khai và transaction nằm trong NotebookServiceImpl. Format/import nhóm notebook được chuẩn hóa. |
| J3 | AiRagService trả dữ liệu nghiệp vụ; controller tạo HTTP envelope; GlobalExceptionHandler xử lý lỗi AI thay vì service tự tạo ResponseEntity. |
| J4 | Có GraphEditorService/Impl riêng với các phương thức nghiệp vụ, GraphEditorAction enum tại client, DTO request và GraphSnapshotResponse/GraphMutationResponse. |
| J5 | PrivilegedChangeListener chuyển từ entity sang listener; cập nhật EntityListeners và các test persistence. |
| J6 | JWT service/filter, CustomUserDetailsService và OAuth2 handlers chuyển sang security; cập nhật config, callers và package test. |
| J7 | CourseAccessPolicy chứa quy tắc owner, đọc course/notebook và sửa graph; graph, chat, feedback và document sử dụng các quy tắc tương ứng. |
| P1 | Các route chat, ingest, graph editor, concept creation/lifecycle và learning path chỉ xác thực/validate rồi gọi services; Cypher chuyển sang db/queries và chạy qua graph_repository. Startup recovery gọi ingestion service. |
| P2 | Các Pydantic request model chuyển sang schemas; graph editor dùng DraftRequest từ schemas thay vì import route khác. |
| P3 | Xóa linker cũ không có caller tĩnh trong repo và hai shim learning_paths chỉ re-export; dùng core/text_utils và retrieval làm nơi triển khai chuẩn. |
| F1 | Notebook page dùng notebookApi cho list/get/create và dùng Notebook/NotebookRequest chung. |
| F2 | Graph và documents thành feature dùng chung, không còn nằm trong courses; cùng một engine phục vụ course và notebook. |
| F3 | PageResponse chuyển sang src/types/api.types.ts; admin dùng alias cùng kiểu, không định nghĩa lại cấu trúc phân trang. |
| F4 | Admin, notebook, profile, ask-ai và learning-path được sắp xếp theo pages/components/services/types/hooks khi có tài nguyên tương ứng. |
| F5 | Tách useKnowledgeGraph, useChatSources, useDocumentProcessing và DocumentProgress khỏi các component điều phối lớn. |

## Cấu trúc chính

Java core:

```text
fu/coreservice/
  controller/              HTTP, validation, ApiResponse
  service/                 Interface nghiệp vụ
    impl/                  Triển khai và transaction
  security/                Xác thực và CourseAccessPolicy
  listener/                Listener JPA/Spring
  repository/              Spring Data
  client/                  Giao tiếp AI core và enum transport
  dto/knowledgegraph/      Request/response graph có kiểu
  entity/                  Entity và enum
  config/                  Cấu hình bean/properties
  exception/               Exception và ánh xạ lỗi HTTP
```

Python AI core:

```text
app/
  api/                     Route, auth nội bộ, validation request
  schemas/                 Pydantic request contracts
  services/                Điều phối chat, ingestion và graph
  db/
    graph_repository.py    Session/query adapter
    queries/               Cypher theo nghiệp vụ
  core/internal_auth.py    Kiểm tra khóa nội bộ dùng chung
  retrieval/               Graph cache và path engine chuẩn
```

Service mở transaction cho một use case và gọi query adapter trong cùng transaction. Việc tách query không thay đổi chuỗi Cypher, thứ tự query, graph lock, expected version hay thời điểm xóa cache. Chưa áp dụng migration database.

Frontend:

```text
src/
  types/api.types.ts
  components/Pagination.tsx
  utils/chatScope.ts
  features/
    knowledge-graph/{components,engine,hooks,services,theme,types}/
    documents/{components,hooks,services,types}/
    courses/{components,hooks,pages,services,types}/
    notebooks/{pages,services,types}/
    ask-ai/{pages,components,hooks,services,styles,types}/
    admin/{pages,components,services,types}/
    profile/{pages,components}/
    learning-path/{pages,components,hooks,services,types}/
```

Các import và test harness được cập nhật theo đường dẫn mới. Một số API module re-export type tường minh để consumer có thể tiếp tục dùng public API của feature; không có bản sao implementation.

## Các hợp đồng được giữ

- URL, phương thức HTTP, scope course/notebook và quy tắc phân quyền.
- JSON request/response hiện có, bao gồm tên `graph_version`, `source_id`, `target_id` và lỗi AI 403/404/409.
- Mutation DTO chỉ serialize các trường áp dụng cho thao tác đó, không thêm các trường null của biến thể khác.
- Alert sau commit, transaction ghi log độc lập và cách giới hạn số cảnh báo.
- SSE, lưu lịch sử chat, citations và dừng luồng.
- Engine 2D/3D, mức zoom, cơ chế ẩn chi tiết theo viewport và chỉnh sửa đồ thị cá nhân.
- Các endpoint AI tổng quát ngoài graph editor vẫn giữ metadata động bằng Map khi cần proxy nguyên vẹn; service không sở hữu status HTTP.

## Kiểm tra

- Java core: 336 test thành công, có test contract DTO/lỗi graph, khởi động ứng dụng và các luồng HTTP qua Spring Security với H2.
- Gateway: 7 test JWT thành công.
- Frontend: 45 test thành công; typecheck, lint và production build thành công.
- Python: 230 test thành công, 1 skip vì Windows không tạo được symlink; 15 bài integration/slow không được chọn trong lượt kiểm tra không phụ thuộc dịch vụ/model thật.
- Test Spring context có fixture trong application-context-test.properties, không cần credential hoặc dịch vụ production.
- Kiểm tra diff không có lỗi whitespace.

Chi tiết xác minh tương thích và giới hạn môi trường được ghi trong `refactor-regression-verification.md`.

Lệnh frontend chạy tại thư mục `D:/all/ki_9/CAPSTONE/FE/edugraph-fe`. Python chạy tại `D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core` với `.venv/Scripts/python.exe -m pytest -m "not integration and not slow"`. Java chạy từ Maven của core-service.
