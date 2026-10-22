import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.core.embedding import EmbeddingModel
from app.db.neo4j_client import neo4j_client
from app.config import settings

# Bật logging cho toàn bộ app.* để theo dõi từng bước pipeline trong log.
# Trước đây chỉ có print() của extraction hiện; các logger.info() (prerequisite, llm)
# không ra console vì root logger chưa có handler ở mức INFO.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
# Bớt ồn từ các thư viện bên thứ ba (chỉ giữ WARNING trở lên).
for _noisy in ("httpx", "httpcore", "openai", "sentence_transformers", "PIL", "matplotlib"):
    logging.getLogger(_noisy).setLevel(logging.WARNING)

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Khởi tạo db
    await neo4j_client.connect()
    
    # Khởi tạo embedding model
    embed_model = EmbeddingModel()
    embed_model.load() # model warmup
    
    from app.api.ingest import recover_processing_documents
    await recover_processing_documents()
    
    yield
    
    # Cleanup
    await neo4j_client.close()

app = FastAPI(title="EduGraph AI Core", lifespan=lifespan)

@app.get("/health")
async def health_check():
    health_status = {"status": "ok", "db": "unknown", "embedding": "unknown"}
    
    # Check DB
    try:
        async with neo4j_client.driver.session() as session:
            result = await session.run("RETURN 1")
            await result.single()
            health_status["db"] = "connected"
    except Exception as e:
        health_status["db"] = f"error: {str(e)}"
        health_status["status"] = "error"
        
    # Check model
    embed_model = EmbeddingModel()
    if getattr(embed_model, '_model', None) is not None:
        health_status["embedding"] = "loaded"
    else:
        health_status["embedding"] = "not_loaded"
        health_status["status"] = "error"
        
    if health_status["status"] == "error":
        return JSONResponse(status_code=503, content=health_status)
    return health_status

from app.api.ingest import router as ingest_router
app.include_router(ingest_router)

from app.api.chat import router as chat_router
app.include_router(chat_router)

from app.api.learning_paths import router as learning_paths_router
app.include_router(learning_paths_router)

from app.api.knowledge_graph import router as knowledge_graph_router
app.include_router(knowledge_graph_router)

from app.api.concept_creation import router as concept_creation_router
app.include_router(concept_creation_router)
