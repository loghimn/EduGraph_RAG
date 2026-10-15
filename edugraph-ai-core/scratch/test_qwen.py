import asyncio
import time
import re
import json
from openai import AsyncOpenAI
from app.config import settings
from app.core.cache import cache, generate_cache_key

client = AsyncOpenAI(api_key=settings.GROQ_API_KEY, base_url="https://api.groq.com/openai/v1")

def extract_json(content: str) -> dict:
    content = re.sub(r'<think>.*?</think>', '', content, flags=re.DOTALL)
    match = re.search(r'\{.*\}', content, flags=re.DOTALL)
    if match:
        return json.loads(match.group(0))
    raise ValueError(f"No JSON object found in: {content[:100]}...")

async def run_json_test(i, chunk_text):
    start = time.perf_counter()
    try:
        response = await client.chat.completions.with_raw_response.create(
            model=settings.MODEL_MAIN,
            messages=[
                {"role": "system", "content": "You are a helpful assistant. Output JSON only."},
                {"role": "user", "content": f"Extract 2 concepts from this text and return JSON {{'concepts': []}}: {chunk_text}"}
            ],
            temperature=0.0
        )
        data = response.parse()
        headers = response.headers
        raw_content = data.choices[0].message.content
        parsed = extract_json(raw_content)
        end = time.perf_counter()
        
        # Log rate limits
        rl = {k: v for k, v in headers.items() if 'ratelimit' in k.lower()}
        return True, (end - start), data.usage.total_tokens, rl
    except Exception as e:
        return False, str(e), 0, {}

async def test_stream():
    print("\n--- STREAM TEST ---")
    try:
        stream = await client.chat.completions.create(
            model=settings.MODEL_MAIN,
            messages=[{"role": "user", "content": "Count from 1 to 5."}],
            stream=True,
            temperature=0.0
        )
        print("Stream output: ", end="", flush=True)
        async for chunk in stream:
            if chunk.choices[0].delta.content:
                print(chunk.choices[0].delta.content, end="", flush=True)
        print("\n[OK] Stream test finished.")
    except Exception as e:
        print(f"\n[FAILED] Stream test failed: {e}")

async def test_cache():
    print("\n--- CACHE TEST ---")
    prompt_ver = "v1"
    data = "This is a test prompt for caching."
    # mock a cache save
    key = generate_cache_key(settings.MODEL_MAIN, prompt_ver, data)
    cache.set(key, {"cached": True})
    
    start = time.perf_counter()
    res = cache.get(key)
    end = time.perf_counter()
    if res and res.get("cached"):
        print(f"[OK] Cache hit! Took {(end - start)*1000:.2f} ms, 0 API requests made.")

async def main():
    chunk = "Machine learning is a subset of artificial intelligence that involves training models on data. Gradient descent is a common optimization algorithm."
    print("--- 20x JSON MODE TEST ---")
    successes = 0
    total_time = 0
    total_tokens = 0
    rate_limits = []
    
    for i in range(20):
        suc, t_or_err, tokens, rl = await run_json_test(i, chunk)
        if suc:
            successes += 1
            total_time += t_or_err
            total_tokens += tokens
            rate_limits.append(rl)
            print(f"Iter {i+1}: OK ({t_or_err:.2f}s, {tokens} tokens) - RL: {rl.get('x-ratelimit-remaining-requests', 'N/A')}")
        else:
            print(f"Iter {i+1}: FAILED -> {t_or_err}")
            
    print(f"\nResult: {successes}/20 parsed successfully.")
    if successes > 0:
        print(f"Average time: {total_time/successes:.2f}s")
        print(f"Average tokens: {total_tokens/successes:.2f}")
    
    await test_stream()
    await test_cache()

if __name__ == "__main__":
    asyncio.run(main())
