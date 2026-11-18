FROM python:3.12-slim-bookworm
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
ARG REQUIREMENTS_LOCK=requirements-cpu.lock
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    HF_HOME=/data/huggingface RAW_DATA_DIR=/data/raw
WORKDIR /app
COPY requirements-cpu.lock requirements-cu121.lock ./
RUN pip install --no-cache-dir --only-binary=:all: --require-hashes --extra-index-url "${TORCH_INDEX_URL}" -r "${REQUIREMENTS_LOCK}" \
    && pip check \
    && useradd --system --uid 10001 --create-home app \
    && mkdir -p /data/raw /data/huggingface /app/.cache \
    && chown -R app:app /data /app
COPY --chown=app:app app ./app
COPY --chown=app:app prompts ./prompts
USER 10001
EXPOSE 8082
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082", "--workers", "1"]
