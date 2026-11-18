import sys
import os

os.environ["INTERNAL_API_KEY"] = "test-internal-api-key"
os.environ["OPENAI_API_KEY"] = "test-openai-api-key"
# Keep optional provider credentials deterministic; never inherit real keys from .env.
os.environ["GEMINI_API_KEY"] = ""
os.environ["GEMINI_API_KEYS"] = ""

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
