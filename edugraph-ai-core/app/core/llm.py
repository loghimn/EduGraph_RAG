import asyncio
import json
import logging
from typing import Any, AsyncGenerator
import openai
from openai import AsyncOpenAI
import httpx
from tenacity import retry, wait_exponential, stop_after_attempt, retry_if_exception_type

from app.config import settings
from app.core.errors import LLMUnavailableError
from app.core.cache import cache, generate_cache_key

logger = logging.getLogger(__name__)

llm_telemetry = []

# Lỗi tạm thời (transient) nên retry với exponential backoff trước khi fallback.
# QUAN TRỌNG: openai SDK KHÔNG ném httpx.* — nó wrap lại thành openai.* exception.
#   - APIConnectionError: lỗi mạng/timeout (APITimeoutError là subclass của nó)
#   - InternalServerError: 5xx, vd Groq "503 over capacity"
#   - RateLimitError: 429
# Giữ httpx.* trong tuple chỉ là phòng hờ (vd caller khác raise trực tiếp).
TRANSIENT_LLM_ERRORS = (
    httpx.RequestError,
    httpx.HTTPStatusError,
    openai.APIConnectionError,
    openai.InternalServerError,
    openai.RateLimitError,
)


def _is_placeholder_key(key: str | None) -> bool:
    """True nếu API key là rỗng hoặc giá trị placeholder mặc định (chưa cấu hình)."""
    if not key:
        return True
    normalized = key.strip().lower()
    return normalized in {"", "dummy", "dummy_key", "changeme"} or normalized.startswith("dummy")


class LLMClient:
    def __init__(self):
        self.groq_client = AsyncOpenAI(
            api_key=settings.GROQ_API_KEY, 
            base_url="https://api.groq.com/openai/v1"
        )
        self.gemini_client = AsyncOpenAI(
            api_key=settings.GEMINI_API_KEY,
            base_url="https://generativelanguage.googleapis.com/v1beta/openai/"
        )
        self.semaphore = asyncio.Semaphore(settings.LLM_CONCURRENCY)

    @retry(
        wait=wait_exponential(multiplier=1, min=2, max=30),
        stop=stop_after_attempt(4),
        retry=retry_if_exception_type(TRANSIENT_LLM_ERRORS),
        reraise=True
    )
    async def _call_provider(self, client: AsyncOpenAI, model: str, prompt: str, json_mode: bool) -> tuple[str, str]:
        import time
        t_call_start = time.perf_counter()
        kwargs = {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.0,
        }
        if json_mode:
            kwargs["response_format"] = {"type": "json_object"}
            
        from app.core.rate_limiter import token_limiter
        estimated_output = getattr(settings, 'MAX_CONCEPTS_PER_GROUP', 15) * 60
        estimated_tokens = (len(prompt) // 4) + estimated_output
        kwargs["max_tokens"] = estimated_output
        
        await token_limiter.wait_and_deduct(estimated_tokens)
            
        try:
            raw_response = await client.chat.completions.with_raw_response.create(**kwargs)
            headers = raw_response.headers
            await token_limiter.update_from_headers(headers)
        except Exception:
            await token_limiter.refund(estimated_tokens)
            raise
            
        parsed = raw_response.parse()
        choice = parsed.choices[0]
        content = choice.message.content
        finish_reason = getattr(choice, 'finish_reason', 'unknown')
        usage = getattr(parsed, 'usage', None)
        
        actual_total = estimated_tokens
        prompt_tokens = 0
        completion_tokens = 0
        if usage:
            actual_total = getattr(usage, 'total_tokens', estimated_tokens)
            prompt_tokens = getattr(usage, 'prompt_tokens', 0)
            completion_tokens = getattr(usage, 'completion_tokens', 0)
            diff = estimated_tokens - actual_total
            print(f"[TokenLog] Est: {estimated_tokens} | Actual: {actual_total} (P: {prompt_tokens}, C: {completion_tokens}) | Diff: {diff}")
            if diff > 0:
                await token_limiter.refund(diff)
        
        call_duration = time.perf_counter() - t_call_start
        llm_telemetry.append({
            "model": model,
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": actual_total,
            "finish_reason": finish_reason,
            "duration_s": call_duration,
            "is_429": False
        })
        
        # Strip Qwen <think>
        if json_mode and "<think>" in content:
            import re
            content = re.sub(r'<think>.*?</think>', '', content, flags=re.DOTALL)
            content = content.strip()
            start = content.find('{')
            end = content.rfind('}')
            if start != -1 and end != -1 and end >= start:
                content = content[start:end+1]
                
        return content, model

    async def generate(self, prompt: str, prompt_version: str, input_data: Any, json_mode: bool = False) -> dict | str:
        # Check cache for main model first
        cache_key_main = generate_cache_key(settings.MODEL_MAIN, prompt_version, input_data)
        if cache_key_main in cache:
            return cache[cache_key_main]
        
        # Check cache for fallback model
        cache_key_fallback = generate_cache_key(settings.MODEL_FALLBACK, prompt_version, input_data)
        if cache_key_fallback in cache:
            return cache[cache_key_fallback]

        async with self.semaphore:
            content = None
            used_model = None
            try:
                content, used_model = await self._call_provider(
                    self.groq_client, settings.MODEL_MAIN, prompt, json_mode
                )
            except Exception as e:
                logger.warning(f"Groq failed: {e}.")
                if _is_placeholder_key(settings.GEMINI_API_KEY):
                    # Không gọi Gemini khi key chưa cấu hình — tránh400 "invalid API key"
                    # làm lu mờ lỗi gốc từ primary.
                    logger.error(
                        "Gemini fallback bị bỏ qua vì GEMINI_API_KEY chưa được cấu hình "
                        "(đang là placeholder). Hãy set key thật trong .env."
                    )
                    raise LLMUnavailableError(
                        f"Primary LLM ({settings.MODEL_MAIN}) failed và GEMINI_API_KEY "
                        f"chưa được cấu hình nên không có fallback. Lỗi gốc: {e}"
                    ) from e
                try:
                    content, used_model = await self._call_provider(
                        self.gemini_client, settings.MODEL_FALLBACK, prompt, json_mode
                    )
                except Exception as e2:
                    logger.error(f"Gemini also failed: {e2}")
                    raise LLMUnavailableError(
                        f"Cả primary ({settings.MODEL_MAIN}) và fallback "
                        f"({settings.MODEL_FALLBACK}) đều thất bại. Primary: {e}. Fallback: {e2}"
                    ) from e2

            # Parse JSON safely if json_mode
            if json_mode:
                try:
                    parsed_content = json.loads(content)
                    if not isinstance(parsed_content, dict):
                        # Gói vào một dict nếu LLM trả về kiểu khác (dù đã yêu cầu JSON object)
                        parsed_content = {"result": parsed_content}
                except json.JSONDecodeError as e:
                    raise ValueError("LLM returned invalid JSON in json_mode") from e
                
                cache_key = generate_cache_key(used_model, prompt_version, input_data)
                cache.set(cache_key, parsed_content)
                return parsed_content

            cache_key = generate_cache_key(used_model, prompt_version, input_data)
            cache.set(cache_key, content)
            return content

    async def generate_stream(self, prompt: str, priority: bool = True) -> AsyncGenerator[str, None]:
        from app.core.rate_limiter import token_limiter
        estimated_output = 600
        estimated_tokens = (len(prompt) // 4) + estimated_output
        await token_limiter.wait_and_deduct(estimated_tokens, priority=priority)
        
        # Khởi tạo hai luồng client
        # Fallback chỉ thực hiện trước token đầu tiên (Timeout 8s)
        try:
            async def open_stream_and_read_first_chunk():
                stream = await self.groq_client.chat.completions.create(
                    model=settings.MODEL_MAIN,
                    messages=[{"role": "user", "content": prompt}],
                    stream=True,
                    temperature=0.0
                )
                return stream, await stream.__anext__()

            stream, first_chunk = await asyncio.wait_for(
                open_stream_and_read_first_chunk(),
                timeout=settings.STREAM_FIRST_TOKEN_TIMEOUT
            )
        except (asyncio.TimeoutError, Exception) as e:
            if isinstance(e, StopAsyncIteration):
                return
            logger.warning(f"Groq stream failed before first token: {e}.")

            if _is_placeholder_key(settings.GEMINI_API_KEY):
                logger.error(
                    "Gemini stream fallback bị bỏ qua vì GEMINI_API_KEY chưa được cấu hình."
                )
                yield (
                    "\n\n[ERROR: LLM không khả dụng — lỗi từ "
                    f"{settings.MODEL_MAIN}: {str(e)}. "
                    "GEMINI_API_KEY chưa được cấu hình nên không có fallback.]"
                )
                return

            # Fallback
            try:
                stream = await self.gemini_client.chat.completions.create(
                    model=settings.MODEL_FALLBACK,
                    messages=[{"role": "user", "content": prompt}],
                    stream=True,
                    temperature=0.0
                )
                async for chunk in stream:
                    if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                        yield chunk.choices[0].delta.content
                return
            except Exception as e2:
                logger.error(f"Gemini stream also failed: {e2}")
                yield f"\n\n[ERROR: Stream interrupted - {str(e2)}]"
                return

        # Nếu đã có token đầu thì tiếp tục stream, nếu lỗi giữa chừng thì báo lỗi chứ không fallback
        try:
            if first_chunk.choices and first_chunk.choices[0].delta and first_chunk.choices[0].delta.content:
                yield first_chunk.choices[0].delta.content
                
            async for chunk in stream:
                if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                    yield chunk.choices[0].delta.content
        except Exception as e:
            logger.error(f"Groq stream interrupted mid-stream: {e}")
            yield f"\n\n[ERROR: Stream interrupted - {str(e)}]"
                
llm_client = LLMClient()
