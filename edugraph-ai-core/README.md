# Dự án hệ thống học tập theo đồ thị khái niệm

Đây là Python service cho hệ thống trích xuất đồ thị khái niệm.

## Cài đặt môi trường

Cài đặt các thư viện lõi (`requirements.txt` đã ghim phiên bản; pin `torch==2.5.1` mặc định là bản PyPI portable để CI/máy CPU cài được).

```bash
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

**Lưu ý GPU (máy dev) vs CPU (máy thật/server):**
Mặc định `pip install -r requirements.txt` dùng torch PyPI portable. Nếu máy dev có GPU NVIDIA và muốn bản CUDA nhanh hơn (~2.5GB), chạy thêm sau đó:
```bash
pip install torch==2.5.1 --index-url https://download.pytorch.org/whl/cu121
pip install fastapi uvicorn pydantic-settings sse-starlette sentence-transformers keybert neo4j openai diskcache tenacity rapidfuzz
```
Sau đó cấu hình trong `.env`: `EMBEDDING_DEVICE=cpu` và `INTERNAL_API_KEY` thành một secret riêng, không rỗng. Service sẽ không khởi động nếu thiếu `INTERNAL_API_KEY`; khi triển khai, cung cấp biến môi trường này qua secret manager hoặc môi trường runtime.

LLM chính gọi trực tiếp OpenAI API. Trong file `.env` cục bộ, cấu hình `OPENAI_API_KEY` với API key từ OpenAI Platform và (tuỳ chọn) `GEMINI_API_KEY` hoặc `GEMINI_API_KEYS` để bật Gemini fallback. Model mặc định là `gpt-5.4-mini` (`MODEL_MAIN`). Ngân sách đầu ra cho JSON mặc định là 3000 token (`LLM_JSON_MAX_OUTPUT_TOKENS`), có thể điều chỉnh theo TPM của tài khoản. OpenAI API được tính phí riêng, không nằm trong gói ChatGPT Plus.

## Windows Troubleshooting
If you see encoding issues on Windows, set the environment variable: set PYTHONUTF8=1 or $env:PYTHONUTF8=1 in PowerShell.
