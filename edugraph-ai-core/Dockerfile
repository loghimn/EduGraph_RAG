FROM python:3.12-slim-bookworm
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    HF_HOME=/data/huggingface RAW_DATA_DIR=/data/raw
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir torch==2.5.1 --index-url ${TORCH_INDEX_URL} \
    && pip install --no-cache-dir -r requirements.txt \
    && useradd --system --uid 10001 --create-home app \
    && mkdir -p /data/raw /data/huggingface /app/.cache \
    && chown -R app:app /data /app
COPY --chown=app:app app ./app
COPY --chown=app:app prompts ./prompts
USER 10001
EXPOSE 8082
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082", "--workers", "1"]
