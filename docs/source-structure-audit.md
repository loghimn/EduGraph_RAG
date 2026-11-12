# Rà soát cấu trúc source — 2026-11-12

> Đây là ảnh chụp kết quả trước refactor. Các mục bên dưới đã được xử lý; cấu trúc và kết quả kiểm tra mới được ghi trong `source-structure-refactor.md`. Một số đường dẫn cũ trong báo cáo này đã được di chuyển.

## Phạm vi và căn cứ

Rà soát tĩnh danh mục file, khai báo package/class/interface, import và các đoạn triển khai liên quan trong:

| Khu vực | File mã nguồn chính |
| --- | ---: |
| Java core `src/main/java` | 178 |
| Java gateway `src/main/java` | 6 |
| Python AI `app` | 46 |
| Frontend `src` (`.ts`, `.tsx`, `.css`) | 103 |
| Tổng | 333 |

Không tính dependency, output build, môi trường Python, file ảnh và test vào số trên. Đây là rà soát cấu trúc, không phải kết quả kiểm thử toàn bộ chức năng.

Không thấy tài liệu quy định kiến trúc bắt buộc trong phạm vi mã nguồn đã quét. Căn cứ chính là mẫu đang được dự án sử dụng: Java `controller → service interface → service/impl → repository/client`; frontend tổ chức theo feature, tách `services`, `types`, `pages`, `components`, `hooks` ở các feature đã có cấu trúc đầy đủ. Phân biệt lệch mẫu hiện có với đề xuất kiến trúc; không coi mọi class `@Service` thiếu interface là sai Spring.

## Java core

### J1. ActivityAlertService không theo mẫu interface/implementation — cần đồng bộ

- Vị trí: [ActivityAlertService.java:28](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/service/ActivityAlertService.java:28).
- `service/ActivityAlertService.java` là concrete class, có `@Service`, logic cảnh báo, cửa sổ thất bại và callback transaction. Chưa có `service/impl/ActivityAlertServiceImpl.java`.
- Hướng sửa: giữ interface các thao tác cảnh báo trong `service`; chuyển state, constructor, Clock, transaction callback và triển khai sang `service/impl/ActivityAlertServiceImpl`. Giữ nguyên hành vi sau commit và tính độc lập của ghi log; điều chỉnh test tạo concrete service.

### J2. NotebookService không theo mẫu interface/implementation — cần đồng bộ

- Vị trí: [NotebookService.java:16](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/service/NotebookService.java:16).
- Là concrete `@Service` ngay trong `service`, trực tiếp truy cập repository và chứa transaction, chưa có `NotebookServiceImpl`.
- Hướng sửa: interface giữ `owns`, `isNotebook`, `get`, `create`, `list`; implementation cùng annotation transaction chuyển sang `service/impl/NotebookServiceImpl`.
- Nhóm notebook cũng lệch cách format phổ biến của Java core: thụt lề một khoảng trắng, nhiều wildcard import. Các file liên quan: [NotebookController.java](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/controller/NotebookController.java), [NotebookResponse.java](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/dto/notebook/NotebookResponse.java), [NotebookRequest.java](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/dto/notebook/NotebookRequest.java). Đây là vấn đề nhất quán, không phải lỗi Java. Không coi việc dùng record là sai.

### J3. AiRagService chứa hợp đồng HTTP — nên tách ranh giới tầng

- Vị trí: [AiRagService.java:9](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/service/AiRagService.java:9), [AiRagServiceImpl.java](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/service/impl/AiRagServiceImpl.java).
- Interface và implementation trả `ResponseEntity<ApiResponse<Map<String, Object>>>`; implementation tự chọn status HTTP và tạo response lỗi/thành công. Các service nghiệp vụ thông thường khác trả DTO/PageResponse và để controller bọc HTTP response.
- Hướng sửa: service trả DTO/kết quả nghiệp vụ, ném exception thích hợp; controller hoặc exception handler tạo HTTP response. Khi tách phải giữ nguyên status và body lỗi AI mà frontend đang sử dụng.
- `ChatMessageService` cũng phụ thuộc `StreamingResponseBody`, nhưng đây là hợp đồng SSE có chủ đích; có thể tách web streaming adapter khi cần, không tự động kết luận sai như một service CRUD.

### J4. Hợp đồng graph dùng chuỗi action và Map tổng quát — nên làm rõ API nghiệp vụ

- Vị trí: [KnowledgeGraphController.java:32](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/controller/KnowledgeGraphController.java:32), [AiRagService.java:11](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/service/AiRagService.java:11), [AiCoreClient.java:231](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/client/AiCoreClient.java:231).
- Nhiều nghiệp vụ đi qua `editGraph(... String action, ... Map body)` và switch chuỗi như `snapshot`, `edge-update`, `concept-review`. Response graph cũng là Map nên compiler không kiểm tra schema.
- Hướng sửa: service graph có phương thức rõ `getSnapshot`, `updateConcept`, `createEdge`, `reviewEdge`…; dùng request/response DTO. Có thể giữ helper HTTP dùng chung nội bộ client. DTO không cần phản chiếu mọi metadata tùy ý của Python nếu đó là chủ đích proxy.

### J5. PrivilegedChangeListener nằm trong entity nhưng là Spring/JPA listener — nên chuyển đúng nhóm trách nhiệm

- Vị trí: [PrivilegedChangeListener.java:17](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/entity/PrivilegedChangeListener.java:17).
- Class có `@Component`, inject `ObjectProvider<ActivityAlertService>`, nhận JPA callbacks và gọi service. Nó không phải entity, tạo phụ thuộc từ package entity lên service.
- Hướng sửa: chuyển sang package `listener` hoặc `infrastructure/persistence/listener`; cập nhật `@EntityListeners` ở User/SystemSettings. Về lâu dài có thể dùng event ứng dụng, nhưng không được đổi sang ghi log trước commit hoặc bỏ mất đường cập nhật đặc quyền cần theo dõi.

### J6. Package config trộn cấu hình với thành phần security thực thi — đề xuất chuẩn hóa package

- Vị trí: [JwtService.java](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/config/JwtService.java), [CustomUserDetailsService.java](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/config/CustomUserDetailsService.java), [JwtAuthenticationFilter.java](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/config/JwtAuthenticationFilter.java), OAuth2LoginSuccessHandler/OAuth2LoginFailureHandler cùng thư mục.
- Các class trên thực thi xác thực/handler, không chỉ khai báo cấu hình.
- Hướng sửa: đưa thành phần thực thi vào `security` và nhóm con nếu cần; giữ SecurityConfig/CorsConfig… trong `config`. Gateway hiện đã có `security` và `filter`.
- Đây là đề xuất sắp xếp package, không phải lỗi dependency injection. CustomUserDetailsService đã implements interface `UserDetailsService` của Spring; không cần tạo thêm interface chỉ để đủ cặp tên.

### J7. Chính sách truy cập course/notebook được lặp trong nhiều service — nên gom policy

- Vị trí: [AiRagServiceImpl.java:211](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/service/impl/AiRagServiceImpl.java:211), [ChatSessionServiceImpl.java:268](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/service/impl/ChatSessionServiceImpl.java:268), [NotebookService.java:19](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/core-service/src/main/java/fu/coreservice/service/NotebookService.java:19), DocumentServiceImpl và MessageFeedbackServiceImpl.
- Có nhiều bản kiểm tra current user, owner, enrollment, notebook; quy tắc thay đổi phải rà nhiều nơi.
- Hướng sửa: gom thành policy/access component có thao tác rõ `requireReadAccess`, `requireGraphWriteAccess`, `requireNotebookOwner`. Không gộp máy móc các phép kiểm tra khác quyền; giữ các truy vấn khóa riêng cho đổi mật khẩu/tài khoản.

## Python AI core

### P1. API routes chứa cả nghiệp vụ và truy vấn Neo4j — nên phân tầng rõ hơn

- File: [graph_editor.py](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/api/graph_editor.py), [concept_creation.py](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/api/concept_creation.py), [concept_lifecycle.py](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/api/concept_lifecycle.py), [ingest.py](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/api/ingest.py).
- Route module chứa Cypher/transaction, kiểm tra cycle, duplicate, version, embedding và điều phối cache. Chat/learning_paths cũng có truy cập DB trong API. Trong khi đó knowledge_graph.py đã gọi lớp truy xuất tại db/repo.py.
- Hướng sửa: API nhận/validate request và chuyển exception; service điều phối nghiệp vụ; repository chứa Cypher. Giữ transaction khóa graph/version như hiện tại khi tách. Đây là đề xuất ranh giới kiến trúc, không áp mẫu interface/Impl của Java cho Python.
- Recovery processing hiện còn được main.py import từ api/ingest.py; nên chuyển tác vụ khởi động sang ingestion service cùng lần tách này.

### P2. Schema dùng chung đang nằm trong route module khác

- Vị trí: [graph_editor.py:13](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/api/graph_editor.py:13) import `DraftRequest` từ `app.api.concept_creation`; định nghĩa tại [concept_creation.py:20](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/api/concept_creation.py:20).
- Hướng sửa: đặt schema dùng chung trong `schemas/concept.py`; route creation/editor cùng import từ đó. Tương tự có thể gom EdgeRequest/ReviewRequest vào schemas/graph.py khi phân tầng.

### P3. Module cũ/shim làm mờ nơi chứa triển khai thật — cần xác minh trước khi dọn

- [services/linker.py](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/services/linker.py) có bản riêng của normalize_concept_key/normalize_text_unaccent, trong khi luồng đang dùng các hàm trong [core/text_utils.py](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/core/text_utils.py). Quét import tĩnh trong source/test không thấy nơi import `app.services.linker`.
- [learning_paths/path_engine.py](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/learning_paths/path_engine.py) và [learning_paths/graph_cache.py](D:/all/ki_9/CAPSTONE/Capstone/EduGraph_RAG/edugraph-ai-core/app/learning_paths/graph_cache.py) chỉ `import *` từ retrieval. Chúng là shim re-export, không phải hai implementation bị copy.
- Hướng sửa: chọn đường dẫn chuẩn và import tường minh; chỉ xóa module cũ sau khi kiểm tra caller ngoài repo/import động và nhu cầu tương thích.

## Frontend

### F1. Notebook page bỏ qua API module đã có và khai báo lại types

- [NotebooksPage.tsx:12](D:/all/ki_9/CAPSTONE/FE/edugraph-fe/src/features/notebooks/NotebooksPage.tsx:12) tự định nghĩa Notebook/NotebookPage và gọi request trực tiếp tại các dòng 39, 42, 54.
- [services/notebookApi.ts](D:/all/ki_9/CAPSTONE/FE/edugraph-fe/src/features/notebooks/services/notebookApi.ts) đã có Notebook và list nhưng chưa được page dùng.
- Hướng sửa: thêm get/create vào notebookApi; page dùng module này; chuyển Notebook/NotebookRequest sang types/notebook.types.ts và dùng PageResponse chung.

### F2. Graph và documents dùng chung nhưng đang thuộc feature courses

- [NotebooksPage.tsx:9](D:/all/ki_9/CAPSTONE/FE/edugraph-fe/src/features/notebooks/NotebooksPage.tsx:9) import CourseDocuments và lazy import KnowledgeGraph từ courses. Các API graph/document, types và engine cũng nằm dưới courses.
- Hướng sửa: đưa phần tái sử dụng sang feature knowledge-graph/documents hoặc shared module rõ ràng; course/notebook chỉ truyền scope, id và capabilities. Không copy thêm một engine riêng cho notebook.

### F3. PageResponse dùng chung bị đặt trong course types và bị định nghĩa lặp

- [course.types.ts:2](D:/all/ki_9/CAPSTONE/FE/edugraph-fe/src/features/courses/types/course.types.ts:2) chứa PageResponse nhưng chat, notebook, learning-path đều import từ courses.
- [activityLogApi.ts:29](D:/all/ki_9/CAPSTONE/FE/edugraph-fe/src/features/admin/activityLogApi.ts:29) và [instructorRegistrationApi.ts:17](D:/all/ki_9/CAPSTONE/FE/edugraph-fe/src/features/admin/instructorRegistrationApi.ts:17) định nghĩa lại cùng cấu trúc phân trang.
- Hướng sửa: common types `PageResponse<T>` dùng chung; LogPage/InstructorRegistrationPage nếu cần chỉ là type alias. Không để type phân trang độc lập với course phụ thuộc course feature.

### F4. Tổ chức thư mục feature chưa nhất quán — ưu tiên thấp

- Courses/auth/dashboard/home đã tách pages/components/services/types; admin còn trộn API, page, dialog và types ở root; notebooks/profile/learning-path/ask-ai có page ở root trong khi một phần tài nguyên đã có thư mục con.
- File admin: ActivityLogsPage.tsx, InstructorRegistrationsPage.tsx, InstructorReviewDialog.tsx, activityLogApi.ts, instructorRegistrationApi.ts.
- Hướng sửa: chọn quy ước feature, áp dụng đồng bộ pages/components/services/types/hooks khi có đủ trách nhiệm; tránh tạo thư mục trống chỉ để đủ mẫu. Đây là vấn đề nhất quán, không phải lỗi React.

### F5. Một số component gộp nhiều trách nhiệm — đề xuất tách theo hành vi

- [AskAI.tsx:100](D:/all/ki_9/CAPSTONE/FE/edugraph-fe/src/features/ask-ai/AskAI.tsx:100): history, source loading, sending/SSE, scroll/focus và phần render workspace.
- [KnowledgeGraph.tsx](D:/all/ki_9/CAPSTONE/FE/edugraph-fe/src/features/courses/components/KnowledgeGraph.tsx): fetch graph, selection, 3D camera, search, toolbar, legend và graph detail orchestration.
- [CourseDocuments.tsx](D:/all/ki_9/CAPSTONE/FE/edugraph-fe/src/features/courses/components/CourseDocuments.tsx): upload, polling processing, điều khiển pipeline và danh sách tài liệu.
- Hướng sửa: tách hooks và view tương ứng như useChatSession, useGraphData, useDocumentProcessing; giữ owner của state rõ ràng. Không kết luận class/component sai chỉ dựa trên số dòng, và không nhất thiết chia nhỏ ngay nếu chưa sửa chức năng liên quan.

## Các điểm đã kiểm tra nhưng chưa thấy lệch mẫu tương tự

- Trong service Java core có 17 interface và đủ 17 implementation tương ứng; đúng hai concrete class trực tiếp trong service là ActivityAlertService và NotebookService.
- Không phát hiện controller Java import trực tiếp repository hoặc service.impl trong lần quét import này.
- Không phát hiện repository Java import tầng controller/service; không phát hiện package declaration lệch đường dẫn trong Java main source.
- Gateway không có cặp nghiệp vụ service/impl cùng kiểu; không có căn cứ bắt gateway tạo cặp này cho JWT helper.
- Hai JwtService thuộc hai ứng dụng có vai trò khác nhau; không xem đó là file thừa chỉ vì trùng tên. Contract token cần đồng bộ nhưng không cần gộp hai ứng dụng.
- Wildcard import xuất hiện rộng trong source; không liệt kê mọi wildcard thành một lỗi kiến trúc.

## Thứ tự nên thực hiện

1. J1/J2: sửa cặp interface/implementation và format nhóm notebook.
2. J3/J4 và F1/F3: làm rõ ranh giới HTTP, typed graph contract, API notebook và type dùng chung.
3. P1/P2: phân tầng Python, giữ transaction/version và quyền truy cập hiện có.
4. J5/J6/J7, F2: chuẩn hóa vị trí listener/security/shared graph và gom policy.
5. P3/F4/F5: dọn module tương thích có kiểm tra, thống nhất thư mục và tách component khi có lợi rõ ràng.

Chưa refactor hoặc thay đổi hành vi production trong đợt rà soát này.
