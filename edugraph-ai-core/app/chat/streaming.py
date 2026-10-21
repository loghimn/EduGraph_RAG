import asyncio
from contextlib import suppress
from typing import AsyncGenerator


async def with_sse_heartbeats(
    events: AsyncGenerator[str, None], interval_seconds: float
) -> AsyncGenerator[str, None]:
    """Keep an SSE read active without cancelling or restarting pending work."""
    pending: asyncio.Task[str] | None = None
    try:
        while True:
            pending = asyncio.create_task(anext(events))
            while pending is not None:
                ready, _ = await asyncio.wait({pending}, timeout=interval_seconds)
                if not ready:
                    yield ": heartbeat\n\n"
                    continue
                completed = pending
                pending = None
                try:
                    frame = completed.result()
                except StopAsyncIteration:
                    return
                yield frame
    finally:
        if pending is not None:
            pending.cancel()
            # Await cancellation before closing the generator: aclose cannot run
            # concurrently with its pending __anext__ call.
            with suppress(asyncio.CancelledError, Exception):
                await pending
        await events.aclose()
