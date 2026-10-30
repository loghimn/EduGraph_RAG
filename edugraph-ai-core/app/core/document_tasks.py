"""Document task ownership for the supported single-worker AI core deployment."""
import asyncio
import logging
from collections.abc import Awaitable, Callable

logger = logging.getLogger(__name__)


class DocumentTasks:
    def __init__(self):
        self.tasks: dict[str, asyncio.Task] = {}
        self.locks: dict[str, asyncio.Lock] = {}

    def lock(self, document_id: str) -> asyncio.Lock:
        return self.locks.setdefault(document_id, asyncio.Lock())

    def active(self, document_id: str) -> bool:
        task = self.tasks.get(document_id)
        return task is not None and not task.done()

    def start(self, document_id: str, work: Callable[[], Awaitable]) -> None:
        # Caller holds the document lock through metadata update and registration.
        if self.active(document_id):
            raise RuntimeError("Document task is already running")
        task = asyncio.create_task(work(), name=f"ingest-{document_id}")
        self.tasks[document_id] = task

        def finished(completed):
            if self.tasks.get(document_id) is completed:
                self.tasks.pop(document_id, None)
            # Retrieve unexpected errors so background failures are never unobserved.
            if not completed.cancelled():
                exc = completed.exception()
                if exc is not None:
                    logger.exception("Background document task failed", exc_info=exc)

        task.add_done_callback(finished)

    async def stop(self, document_id: str) -> None:
        task = self.tasks.get(document_id)
        if task is not None:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            if self.tasks.get(document_id) is task:
                self.tasks.pop(document_id, None)

    async def shutdown(self) -> None:
        tasks = list(self.tasks.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self.tasks.clear()


async def gather_owned(*work):
    """Cancel AND drain sibling tasks on failure or parent cancellation."""
    tasks = [asyncio.create_task(item) for item in work]
    try:
        return await asyncio.gather(*tasks)
    except BaseException:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise


async def to_thread_owned(function, *args, **kwargs):
    """CPU threads cannot be killed; drain them before releasing shared locks."""
    task = asyncio.create_task(asyncio.to_thread(function, *args, **kwargs))
    try:
        return await asyncio.shield(task)
    except asyncio.CancelledError:
        await asyncio.gather(task, return_exceptions=True)
        raise


document_tasks = DocumentTasks()
