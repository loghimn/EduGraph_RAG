# Dự án hệ thống học tập theo đồ thị khái niệm

Đây là Python service cho hệ thống trích xuất đồ thị khái niệm.

## Cài đặt môi trường

Cài đặt các thư viện lõi (file `requirements.txt` đã được ghim phiên bản kèm thư viện PyTorch bản CUDA `+cu121` cho máy dev).

```bash
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

**Lưu ý nếu máy bạn không có GPU (máy thật/server):**
Bạn không nên tải bản `+cu121` vì file rất nặng (~2.5GB). Hãy chạy lệnh sau để tải PyTorch bản CPU:
```bash
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install fastapi uvicorn pydantic-settings sse-starlette sentence-transformers keybert neo4j openai diskcache tenacity
```
Sau đó cấu hình trong `.env`: `EMBEDDING_DEVICE=cpu` và `INTERNAL_API_KEY` thành một secret riêng, không rỗng. Service sẽ không khởi động nếu thiếu `INTERNAL_API_KEY`; khi triển khai, cung cấp biến môi trường này qua secret manager hoặc môi trường runtime.

## Windows Troubleshooting
If you see encoding issues on Windows, set the environment variable: set PYTHONUTF8=1 or $env:PYTHONUTF8=1 in PowerShell.
