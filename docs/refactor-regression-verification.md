# Xác minh hồi quy sau refactor — 2026-11-12

## Kết quả kiểm tra

| Nhóm | Kết quả |
| --- | --- |
| Java core | 336 passed, 0 failure/error |
| Gateway JWT | 7 passed, 0 failure/error |
| Python không phụ thuộc integration/model thật | 230 passed, 1 skipped, 15 deselected |
| Frontend | 45 passed; typecheck, lint và production build thành công |

Python skip một bài vì Windows không cho tạo symlink. Các bài integration/slow cần model hoặc dịch vụ thật không nằm trong kết quả pass trên.

## Kiểm tra thêm sau lượt refactor

`RefactorHttpRegressionTest` dùng application context thật, filter chain Spring Security thật, controller/service/repository thật và database H2 riêng. Chỉ process AI bên ngoài được giả lập.

Các luồng đã được kiểm tra qua HTTP:

1. Đăng ký sinh viên, đăng nhập và lấy JWT.
2. Tạo sổ tay, chuẩn hóa title/domain, đọc lại sổ tay.
3. Từ chối truy cập khi chưa đăng nhập.
4. Tạo hội thoại trong sổ tay và đọc lịch sử hội thoại.
5. Cập nhật username, đổi mật khẩu, từ chối JWT cũ, đăng nhập bằng mật khẩu mới và đọc lại sổ tay cũ.
6. Đăng ký giảng viên giữ trạng thái PENDING, không cấp token và chưa được đăng nhập.
7. Sinh viên không thể tự duyệt tài khoản giảng viên.
8. Admin đăng nhập, xem danh sách chờ, duyệt và cho phép giảng viên đăng nhập.
9. Chủ sổ tay đọc snapshot graph và tạo quan hệ qua service đã tách; JSON giữ các tên trường graph_version/review_status/status.
10. Người dùng khác không thể đọc sổ tay, đọc snapshot hoặc tạo cạnh; AI client không được gọi trong các trường hợp bị từ chối.

Tài nguyên không thuộc quyền truy cập giữ mã 404 hiện có của COURSE_ACCESS_DENIED. Không đổi production thành 403 chỉ để khớp giả định của test.

`AiCoreClientTest` bổ sung test nhận snapshot JSON qua HTTP giả lập và serialize lại DTO: payload giữ nguyên aliases, ngôn ngữ, trạng thái duyệt, evidence, nullable evidence_chunk_id, reviewer và timestamp.

## Đối chiếu source trước/sau

Đối chiếu với phiên bản source trong Git index trước lần refactor:

- 35 body hàm Python tương đương về AST sau khi chuẩn hóa việc chuyển Cypher sang query catalog, gọi query qua repository và chuyển auth nội bộ ra route.
- 21 route giữ nguyên tên, decorator HTTP và danh sách tham số/default/validation.
- 7 request model giữ nguyên AST trường và validator.
- Các chuỗi Cypher trong query catalog được kiểm tra bằng AST trước/sau khi format để không đổi nội dung truy vấn.

Các kiểm tra tự động khác vẫn bao phủ SSE, citations, dừng luồng, transaction chat, xử lý tài liệu, phiên bản graph, chu trình tiên quyết, lưu lộ trình, quyền course/notebook và các hành vi frontend đã có.

## Giới hạn xác minh

Không phát hiện dịch vụ local đang phục vụ HTTP tại các cổng 5173, 8080, 8081 và 8082 khi kiểm tra. Vì vậy kết quả này không phải một phiên chạy xuyên toàn bộ hệ thống bằng trình duyệt với Supabase, Neo4j, Google OAuth, email và model thật.

Không có căn cứ khẳng định tuyệt đối mọi tích hợp bên ngoài hoạt động chỉ từ test/build. Cần chạy một lượt nghiệm thu trên môi trường test đủ dịch vụ với dữ liệu thử cho upload/xử lý tài liệu, câu trả lời RAG thật, email/OTP và Google OAuth trước khi coi toàn bộ hệ thống đã được xác minh thực tế.
