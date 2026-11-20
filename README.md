# EduGraph-RAG

Hệ thống học tập từ tài liệu, đồ thị khái niệm, quan hệ tiên quyết, lộ trình học và hỏi đáp AI có trích dẫn.

## Thành phần

| Thành phần | Vai trò | Cổng local |
| --- | --- | --- |
| `gateway-service` | Cổng API và xác thực yêu cầu | 8080 |
| `core-service` | Tài khoản, khóa học, tài liệu, lộ trình và quản trị | 8081 |
| `edugraph-ai-core` | Xử lý tài liệu, Neo4j, embedding và GraphRAG | 8082 |
| Frontend ở repository `FE/edugraph-fe` | Giao diện web | 5173 |

## Chạy và cấu hình

- Hai service Java dùng cấu hình trong `src/main/resources/application.yml` của từng module. Cung cấp các biến môi trường được tham chiếu trong cấu hình, rồi chạy main class bằng IDE hoặc `mvnw spring-boot:run` trong module tương ứng.
- Hướng dẫn cài thư viện, cấu hình LLM/GPU và chạy Python: [AI core README](edugraph-ai-core/README.md).
- Cấu hình `INTERNAL_API_KEY` của Python và `AI_CORE_INTERNAL_KEY` của Java phải trùng nhau.
- Giữ thông tin kết nối database, JWT, mail và API key trong môi trường local/runtime; không commit secret.

## CI/CD và triển khai

Xem [hướng dẫn CI/CD cho edugraph-rag.io.vn](deploy/README.md): kiểm tra tự động, Docker image CPU/GPU, deploy về máy Windows, Cloudflare Tunnel và rollback.

## Tra cứu API

- Giảng viên mở chi tiết khóa học → **Công cụ giảng dạy**; admin mở mục này từ sidebar. Trang `/teaching-tools` cho phép chọn khóa học, chỉnh ngưỡng vector search (0–1), độ sâu tiên quyết (0–6) và hướng dẫn AI giảng giải. Cấu hình áp dụng từ câu hỏi tiếp theo, không thay đổi dữ liệu đã trích xuất.
- Chat giải thích tự lấy thêm nguồn của các tiên quyết chưa khai báo nắm vững, dừng duyệt tại concept đã mastered và giữ giới hạn số trích dẫn. Độ sâu 0 tắt bước bổ sung này. Ngưỡng tương đồng chỉ lọc kênh vector; nguồn trực tiếp và tìm kiếm từ khóa vẫn hoạt động.
- Khi môn đang hỏi không có nguồn cho concept hoặc cạnh, chat tìm metadata trong các môn **đang public**, không gồm notebook. Cạnh phải khớp đúng chiều (nếu người dùng chỉ rõ chiều), không chỉ trùng tên hai đầu. Hệ thống gợi ý tối đa 5 môn, ưu tiên môn đã tham gia: người chưa tham gia có nút **Tham gia và mở môn này**, người đã tham gia có nút **Chuyển qua môn này**. Chỉ đăng ký/chuyển khi người dùng chọn; câu hỏi và context concept/cạnh được điền sẵn ở chat môn mới, không tự gửi. Gợi ý được lưu trong lịch sử, kiểm tra lại trạng thái public và tham gia khi tải lại; không dùng đoạn tài liệu của môn khác để trả lời trong môn hiện tại.
- Thống kê theo khóa học gồm lượt hỏi có concept mục tiêu, số người hỏi, người hỏi lại, phản hồi không hài lòng, trạng thái đang học và đã nắm vững. Lượt hỏi tính trên câu trả lời hoàn tất của sinh viên từ khi tính năng được bật; chưa có dữ liệu hồi tố cho hội thoại cũ. Khoảng ngày áp dụng cho lượt hỏi, mastery phản ánh trạng thái hiện tại.
- Trong trình sửa concept, giảng viên đánh giá độ khó 1–5 hoặc để chưa đánh giá (mức trung lập 3). Lộ trình mới ưu tiên concept dễ hơn khi đủ tiên quyết; lộ trình đã lưu giữ nguyên thứ tự và độ khó tại thời điểm tạo.
- API mới: `GET /api/teaching-courses`, `GET/PUT /api/courses/{courseId}/ai-settings`, `GET /api/courses/{courseId}/learning-analytics?days=30`. Chỉ giảng viên sở hữu khóa học và admin được quản lý. Hibernate `ddl-auto=update` bổ sung bảng `concept_queries` và các cột độ khó của bước học khi khởi động core-service.

- Java: `http://localhost:8080/swagger-ui/index.html`.
- Python: `http://localhost:8082/docs`.
- [API trạng thái kiến thức người học](core-service/MASTERY_API.md).
