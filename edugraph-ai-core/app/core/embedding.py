import asyncio
import torch
from sentence_transformers import SentenceTransformer
from app.config import settings
from app.core.document_tasks import to_thread_owned

class EmbeddingModel:
    _instance = None
    _lock = asyncio.Lock()

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(EmbeddingModel, cls).__new__(cls)
            cls._instance._model = None
            cls._instance._encode_lock = asyncio.Lock()
        return cls._instance

    def load(self):
        if not hasattr(self, "load_count"):
            self.load_count = 0
        self.load_count += 1
        print(f"[Model] bge-m3 được gọi load() lần thứ: {self.load_count}")
        if self._model is not None:
            return
        
        device = settings.EMBEDDING_DEVICE
        if device == "auto" or device == "cuda":
            if torch.cuda.is_available():
                device = "cuda"
            else:
                device = "cpu"
        else:
            device = "cpu"
            
        model_kwargs = {}
        if device == "cuda":
            # Sử dụng fp16 trên GPU
            model_kwargs["model_kwargs"] = {"torch_dtype": torch.float16}

        # Khởi tạo model singleton bge-m3
        self._model = SentenceTransformer("BAAI/bge-m3", device=device, **model_kwargs)
        
        # Warmup bằng một câu văn ngẫu nhiên
        self._model.encode(["warmup"], batch_size=1)

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        if self._model is None:
            raise RuntimeError("Model chưa được load. Vui lòng gọi load() trong lifespan.")
        
        if not texts:
            return []

        batch_size = settings.EMBED_BATCH_SIZE

        # Đảm bảo chỉ có một lần encode chạy tại một thời điểm
        async with self._encode_lock:
            while batch_size >= 1:
                try:
                    # Chạy qua to_thread để không chặn event loop
                    embeddings = await to_thread_owned(
                        self._model.encode, texts, batch_size=batch_size, normalize_embeddings=True
                    )
                    return embeddings.tolist() if hasattr(embeddings, 'tolist') else embeddings
                except torch.cuda.OutOfMemoryError:
                    batch_size //= 2
                    if torch.cuda.is_available():
                        torch.cuda.empty_cache()
                    if batch_size < 1:
                        raise RuntimeError("Hết VRAM ngay cả với batch_size=1.")
                except Exception as e:
                    if "cuda out of memory" in str(e).lower() or "allocate" in str(e).lower():
                        batch_size //= 2
                        if torch.cuda.is_available():
                            torch.cuda.empty_cache()
                        if batch_size < 1:
                            raise RuntimeError("Hết VRAM ngay cả với batch_size=1.")
                    else:
                        raise e

embed_model = EmbeddingModel()
