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


def estimate_request_tokens(prompt: str, json_mode: bool = False) -> int:
    """Use the same input/output reservation for batching and rate limiting."""
    output_tokens = settings.LLM_JSON_MAX_OUTPUT_TOKENS if json_mode else 900
    return len(prompt) // 4 + output_tokens

# Lỗi tạm thời (transient) nên retry với exponential backoff trước khi fallback.
# QUAN TRỌNG: openai SDK KHÔNG ném httpx.* — nó wrap lại thành openai.* exception.
#   - APIConnectionError: lỗi mạng/timeout (APITimeoutError là subclass của nó)
#   - InternalServerError: 5xx errors from the configured provider
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


def _configured_gemini_keys() -> list[str]:
    keys = [
        key.strip()
        for key in settings.GEMINI_API_KEYS.split(",")
        if key.strip()
    ]
    if settings.GEMINI_API_KEY.strip():
        keys.append(settings.GEMINI_API_KEY.strip())
    return list(dict.fromkeys(key for key in keys if not _is_placeholder_key(key)))


def _completion_request_kwargs(
    model: str,
    prompt: str,
    json_mode: bool,
    max_tokens: int | None = None,
    stream: bool = False,
) -> dict[str, Any]:
    """Build Chat Completions arguments compatible with current GPT model families."""
    kwargs: dict[str, Any] = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
    }
    reasoning_model = model.startswith(("gpt-5", "gpt-6"))
    if reasoning_model:
        kwargs["reasoning_effort"] = "none" if json_mode else "low"
    else:
        kwargs["temperature"] = 0.0
    if json_mode:
        kwargs["response_format"] = {"type": "json_object"}
    if max_tokens is not None:
        kwargs["max_completion_tokens" if reasoning_model else "max_tokens"] = max_tokens
    if stream:
        kwargs["stream"] = True
    return kwargs


class LLMClient:
    def __init__(self):
        openai_api_key = settings.OPENAI_API_KEY.strip()
        if _is_placeholder_key(openai_api_key):
            raise ValueError(
                "OPENAI_API_KEY is missing or is a placeholder. "
                "Set it in the process environment or edugraph-ai-core/.env."
            )
        self.openai_client = AsyncOpenAI(api_key=openai_api_key)
        self.gemini_clients = [
            AsyncOpenAI(
                api_key=key,
                base_url="https://generativelanguage.googleapis.com/v1beta/openai/"
            )
            for key in _configured_gemini_keys()
        ]
        if not self.gemini_clients:
            self.gemini_clients.append(
                AsyncOpenAI(
                    api_key=settings.GEMINI_API_KEY,
                    base_url="https://generativelanguage.googleapis.com/v1beta/openai/"
                )
            )
        self.gemini_client = self.gemini_clients[0]
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
        from app.core.rate_limiter import token_limiter
        estimated_output = (
            settings.LLM_JSON_MAX_OUTPUT_TOKENS if json_mode else 900
        )
        estimated_tokens = estimate_request_tokens(prompt, json_mode)
        kwargs = _completion_request_kwargs(model, prompt, json_mode, estimated_output)
        
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
        content = choice.message.content or ""
        finish_reason = getattr(choice, 'finish_reason', 'unknown')
        usage = getattr(parsed, 'usage', None)
        
        actual_total = estimated_tokens
        prompt_tokens = 0
        completion_tokens = 0
        reasoning_tokens = 0
        if usage:
            actual_total = getattr(usage, 'total_tokens', estimated_tokens)
            prompt_tokens = getattr(usage, 'prompt_tokens', 0)
            completion_tokens = getattr(usage, 'completion_tokens', 0)
            completion_details = getattr(usage, 'completion_tokens_details', None)
            reasoning_tokens = getattr(completion_details, 'reasoning_tokens', 0) or 0
            diff = estimated_tokens - actual_total
            print(
                f"[TokenLog] Est: {estimated_tokens} | Actual: {actual_total} "
                f"(P: {prompt_tokens}, C: {completion_tokens}, R: {reasoning_tokens}) "
                f"| Finish: {finish_reason} | Diff: {diff}"
            )
            if diff > 0:
                await token_limiter.refund(diff)
        elif json_mode:
            logger.info(
                "LLM JSON response metadata: model=%s finish_reason=%s",
                model,
                finish_reason,
            )
        
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
        
        # Strip reasoning text some compatible providers may prepend to JSON.
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
            used_client = self.openai_client
            try:
                content, used_model = await self._call_provider(
                    used_client, settings.MODEL_MAIN, prompt, json_mode
                )
            except Exception as e:
                logger.warning(f"OpenAI failed: {e}.")
                if not _configured_gemini_keys():
                    # Không gọi Gemini khi key chưa cấu hình — tránh400 "invalid API key"
                    # làm lu mờ lỗi gốc từ primary.
                    logger.error(
                        "Gemini fallback bị bỏ qua vì chưa cấu hình GEMINI_API_KEY hoặc GEMINI_API_KEYS "
                        "(đang là placeholder). Hãy set key thật trong .env."
                    )
                    raise LLMUnavailableError(
                        f"Primary LLM ({settings.MODEL_MAIN}) failed và Gemini API key "
                        f"chưa được cấu hình nên không có fallback. Lỗi gốc: {e}"
                    ) from e
                gemini_errors = []
                for key_index, gemini_client in enumerate(self.gemini_clients, start=1):
                    try:
                        used_client = gemini_client
                        content, used_model = await self._call_provider(
                            used_client, settings.MODEL_FALLBACK, prompt, json_mode
                        )
                        break
                    except Exception as gemini_error:
                        gemini_errors.append(gemini_error)
                        logger.warning(
                            "Gemini fallback key %d/%d failed: %s",
                            key_index,
                            len(self.gemini_clients),
                            gemini_error,
                        )
                else:
                    gemini_error = gemini_errors[-1]
                    logger.error("All Gemini fallback keys failed.")
                    raise LLMUnavailableError(
                        f"Cả primary ({settings.MODEL_MAIN}) và fallback "
                        f"({settings.MODEL_FALLBACK}) đều thất bại. Primary: {e}. "
                        f"Gemini key failures: {'; '.join(map(str, gemini_errors))}"
                    ) from gemini_error

            # Parse JSON safely if json_mode
            if json_mode:
                try:
                    parsed_content = json.loads(content)
                except json.JSONDecodeError as e:
                    logger.warning(
                        "Model %s returned invalid JSON; retrying once with stricter formatting instructions.",
                        used_model,
                    )
                    repair_prompt = (
                        f"{prompt}\n\n"
                        "IMPORTANT: Your previous response was not valid JSON. Complete the original task "
                        "again and return exactly one valid JSON object. Use double-quoted property names "
                        "and strings. Do not include Markdown fences or text outside the JSON object."
                    )
                    try:
                        content, used_model = await self._call_provider(
                            used_client, used_model, repair_prompt, json_mode=True
                        )
                    except Exception as retry_error:
                        raise LLMUnavailableError(
                            f"LLM provider failed during JSON formatting retry for "
                            f"model {used_model}: {retry_error}"
                        ) from retry_error

                    try:
                        parsed_content = json.loads(content)
                    except json.JSONDecodeError as retry_error:
                        raise ValueError(
                            "LLM returned invalid JSON in json_mode after one formatting retry"
                        ) from retry_error

                if not isinstance(parsed_content, dict):
                    # Wrap a non-object JSON value for callers expecting a dictionary.
                    parsed_content = {"result": parsed_content}
                
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
        
        # Khởi tạo luồng OpenAI chính.
        # Fallback chỉ thực hiện trước token đầu tiên (Timeout 8s)
        try:
            async def open_stream_and_read_first_chunk():
                stream = await self.openai_client.chat.completions.create(
                    **_completion_request_kwargs(
                        settings.MODEL_MAIN, prompt, json_mode=False, stream=True
                    )
                )
                return stream, await stream.__anext__()

            stream, first_chunk = await asyncio.wait_for(
                open_stream_and_read_first_chunk(),
                timeout=settings.STREAM_FIRST_TOKEN_TIMEOUT
            )
        except (asyncio.TimeoutError, Exception) as e:
            if isinstance(e, StopAsyncIteration):
                return
            logger.warning(f"OpenAI stream failed before first token: {e}.")

            if not _configured_gemini_keys():
                logger.error(
                    "Gemini stream fallback bị bỏ qua vì chưa cấu hình Gemini API key."
                )
                yield (
                    "\n\n[ERROR: LLM không khả dụng — lỗi từ "
                    f"{settings.MODEL_MAIN}: {str(e)}. "
                    "Gemini API key chưa được cấu hình nên không có fallback.]"
                )
                return

            gemini_stream = None
            first_gemini_chunk = None
            gemini_errors = []
            for key_index, gemini_client in enumerate(self.gemini_clients, start=1):
                try:
                    async def open_gemini_stream_and_read_first_chunk():
                        gemini_stream = await gemini_client.chat.completions.create(
                            **_completion_request_kwargs(
                                settings.MODEL_FALLBACK, prompt, json_mode=False, stream=True
                            )
                        )
                        return gemini_stream, await gemini_stream.__anext__()

                    gemini_stream, first_gemini_chunk = await asyncio.wait_for(
                        open_gemini_stream_and_read_first_chunk(),
                        timeout=settings.STREAM_FIRST_TOKEN_TIMEOUT
                    )
                    break
                except StopAsyncIteration:
                    return
                except Exception as gemini_error:
                    gemini_errors.append(gemini_error)
                    logger.warning(
                        "Gemini stream fallback key %d/%d failed: %s",
                        key_index,
                        len(self.gemini_clients),
                        gemini_error,
                    )

            if gemini_stream is None:
                logger.error("All Gemini stream fallback keys failed.")
                yield f"\n\n[ERROR: Stream interrupted - {'; '.join(map(str, gemini_errors))}]"
                return
            try:
                if (
                    first_gemini_chunk.choices
                    and first_gemini_chunk.choices[0].delta
                    and first_gemini_chunk.choices[0].delta.content
                ):
                    yield first_gemini_chunk.choices[0].delta.content
                async for chunk in gemini_stream:
                    if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                        yield chunk.choices[0].delta.content
            except Exception as gemini_error:
                logger.error(f"Gemini stream interrupted mid-stream: {gemini_error}")
                yield f"\n\n[ERROR: Stream interrupted - {str(gemini_error)}]"
            return

        # Nếu đã có token đầu thì tiếp tục stream, nếu lỗi giữa chừng thì báo lỗi chứ không fallback
        try:
            if first_chunk.choices and first_chunk.choices[0].delta and first_chunk.choices[0].delta.content:
                yield first_chunk.choices[0].delta.content
                
            async for chunk in stream:
                if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                    yield chunk.choices[0].delta.content
        except Exception as e:
            logger.error(f"OpenAI stream interrupted mid-stream: {e}")
            yield f"\n\n[ERROR: Stream interrupted - {str(e)}]"
                
llm_client = LLMClient()
