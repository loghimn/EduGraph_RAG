import sys
import os

os.environ["INTERNAL_API_KEY"] = "test-internal-api-key"
os.environ["OPENAI_API_KEY"] = "test-openai-api-key"
# Keep optional provider credentials deterministic; never inherit real keys from .env.
os.environ["GEMINI_API_KEY"] = ""
os.environ["GEMINI_API_KEYS"] = ""
# Response-cache scenarios enable caching explicitly with isolated storage.
os.environ["CHAT_ANSWER_CACHE_TTL_SECONDS"] = "0"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
