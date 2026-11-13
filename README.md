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

## Tra cứu API

- Java: `http://localhost:8080/swagger-ui/index.html`.
- Python: `http://localhost:8082/docs`.
- [API trạng thái kiến thức người học](core-service/MASTERY_API.md).
