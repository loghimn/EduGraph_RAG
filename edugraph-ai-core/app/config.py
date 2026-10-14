import os
from typing import Literal
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    PIPELINE_TPM_SHARE: float = 0.8

    # Neo4j Aura
    NEO4J_URI: str = "neo4j://localhost:7687"
    NEO4J_USERNAME: str = "neo4j"
    NEO4J_PASSWORD: str = "password"

    # LLM Keys
    GROQ_API_KEY: str = "dummy_key"
    GEMINI_API_KEY: str = ""
    INTERNAL_API_KEY: str = "dummy_key"
    
    # Path
    RAW_DATA_DIR: str = "/data/raw"

    # Upload limit cho endpoint nhận file từ Java core-service (đồng bộ với max-file-size 40MB của Spring)
    MAX_UPLOAD_MB: int = 40

    # LLM Config
    MODEL_MAIN: str = "qwen/qwen3.8-27b"
    MODEL_FALLBACK: str = "gemini-2.5-flash"
    LLM_CONCURRENCY: int = 3
    STREAM_FIRST_TOKEN_TIMEOUT: float = 8.0

    # Embedding Config
    EMBEDDING_DEVICE: Literal["auto", "cuda", "cpu"] = "auto"
    EMBED_BATCH_SIZE: int = 8

    # Extraction Constants
    CHUNK_TOKENS: int = 500
    CHUNK_OVERLAP: int = 50
    KEYBERT_TOP_N: int = 50
    CHUNKS_PER_LLM_CALL: int = 4
    FUZZY_THRESHOLD: int = 90
    MERGE_COSINE_MIN: float = 0.85
    ALIAS_SCAN_MIN_LEN: int = 4
    ACRONYM_MIN_LEN: int = 2
    CONCEPT_EMBED_MAX_ALIASES: int = 3
    STALE_PROCESSING_MINUTES: int = 15
    NEO4J_BATCH_SIZE: int = 100
    ENABLE_HEADER_CLEANING: bool = False
    HEADER_REPEAT_RATIO: float = 0.5

    # Prerequisite Constants
    PREREQ_TOPK_COSINE: int = 30
    PREREQ_CANDIDATES: int = 15
    PREREQ_MAX_PER_CONCEPT: int = 5
    JUDGE_MAX_EVIDENCE_CHUNKS: int = 2
    JUDGE_CHUNK_CHARS: int = 800
    W_LLM: float = 0.8
    W_RULE: float = 0.2
    EDGE_MIN_SCORE: float = 0.4

    # Chat Constants
    GRAPH_VERSION_CHECK_INTERVAL: float = 10.0
    VECTOR_OVERFETCH_K: int = 50
    CHAT_MAX_CHUNKS: int = 6
    MIN_CHARS_PER_PAGE: int = 100
    ALIAS_FUZZY_MIN_LEN: int = 4

    # Learning Paths Constants
    ISOLATED_CONCEPTS_WARNING_THRESHOLD: float = 0.2

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

settings = Settings()
