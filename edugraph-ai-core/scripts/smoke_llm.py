import asyncio
import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.llm import llm_client
from app.core.errors import LLMUnavailableError
from app.config import settings

async def test_model(model_name: str, client_attr: str):
    client = getattr(llm_client, client_attr)
    print(f"\n--- Testing Model: {model_name} ---")
    try:
        # Text mode
        response = await client.chat.completions.create(
            model=model_name,
            messages=[{"role": "user", "content": "Say 'hello world' in lowercase."}],
            temperature=0.0
        )
        print(f"[OK] Text response: {response.choices[0].message.content.strip()}")
        
        # JSON mode
        response_json = await client.chat.completions.create(
            model=model_name,
            messages=[{"role": "user", "content": "Return a JSON object with key 'message' and value 'hello'."}],
            response_format={"type": "json_object"},
            temperature=0.0
        )
        content_json = response_json.choices[0].message.content.strip()
        print(f"[OK] JSON response: {content_json}")
        
        if not content_json.startswith("{") or not content_json.endswith("}"):
            print(f"[WARNING] Model {model_name} did not return a valid JSON object format natively!")
            
    except Exception as e:
        # Check for model not found error without printing API keys (which is the default Python stacktrace anyway, but let's be careful)
        error_msg = str(e)
        if "does not exist" in error_msg.lower() or "not found" in error_msg.lower() or "404" in error_msg:
            print(f"[FAILED] Model '{model_name}' DOES NOT EXIST or is not available on this API. Please check the model name.")
        else:
            print(f"[FAILED] Error calling {model_name}: {e.__class__.__name__} - Please ensure your API key is correct (API key is not logged here).")

async def main():
    print("Testing LLM connections...")
    
    await test_model(settings.MODEL_MAIN, "openai_client")
    await test_model(settings.MODEL_FALLBACK, "gemini_client")
    
    print("\nTesting unified LLMClient logic...")
    try:
        res = await llm_client.generate("Return a JSON object with a test key.", "v1", {"test": 1}, json_mode=True)
        print(f"[OK] Unified generate() JSON: {res}")
        if not isinstance(res, dict):
             print(f"[ERROR] generate() did not return a dictionary object! Type: {type(res)}")
    except Exception as e:
        print(f"[ERROR] Unified generate() failed: {e}")

if __name__ == "__main__":
    asyncio.run(main())
