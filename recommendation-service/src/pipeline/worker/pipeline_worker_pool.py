import asyncio
import contextvars
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor
from contextlib import suppress
from typing import TypeVar

from shared.config.env import positive_int_env

# Pipeline runs a replica executes at once, over gRPC and RabbitMQ together
PIPELINE_WORKER_POOL_SIZE = positive_int_env("PIPELINE_WORKER_POOL_SIZE", 2)

T = TypeVar("T")


class PipelineWorkerPoolSaturatedError(Exception):
    """Every worker is busy and the caller asked not to wait."""


class PipelineWorkerPool:
    """Runs the synchronous, heavy pipeline off the event loop, so consumers,
    probes and the outbox relay keep running. Bounded: a slot is held until the
    run really ends, including when the caller was cancelled meanwhile, because a
    running thread cannot be stopped. Each run sees the caller's contextvars
    (correlation id, trace context). Call it from a single event loop."""

    def __init__(self, size: int = PIPELINE_WORKER_POOL_SIZE):
        if size <= 0:
            raise ValueError(f"Pipeline worker pool size must be positive, got {size}")
        self._executor = ThreadPoolExecutor(max_workers=size, thread_name_prefix="pipeline-worker")
        self._slots = asyncio.Semaphore(size)

    async def run(self, function: Callable[[], T]) -> T:
        """Waits for a free worker (RabbitMQ: the message stays unacknowledged)."""
        await self._slots.acquire()
        return await self._submit(function)

    async def try_run(self, function: Callable[[], T]) -> T:
        """Fails right away when no worker is free (gRPC: the client backs off)."""
        if self._slots.locked():
            raise PipelineWorkerPoolSaturatedError()
        await self._slots.acquire()  # Does not suspend: a slot is free
        return await self._submit(function)

    async def shutdown(self) -> None:
        await asyncio.to_thread(self._executor.shutdown, wait=True)

    async def _submit(self, function: Callable[[], T]) -> T:
        loop = asyncio.get_running_loop()
        caller_context = contextvars.copy_context()

        def run_then_release() -> T:
            try:
                return caller_context.run(function)
            finally:
                self._release_from_any_thread(loop)

        try:
            future: Future[T] = self._executor.submit(run_then_release)
        except BaseException:
            self._slots.release()
            raise
        # Cancelled before a thread took it, so run_then_release will never run
        future.add_done_callback(lambda done: done.cancelled() and self._release_from_any_thread(loop))
        try:
            return await asyncio.wrap_future(future)
        finally:
            # A run that already ended resumes this coroutine without passing through the
            # loop, where its release is pending: let it run, so the slot is free on return
            await asyncio.sleep(0)

    def _release_from_any_thread(self, loop: asyncio.AbstractEventLoop) -> None:
        with suppress(RuntimeError):  # The loop closed during shutdown: nobody waits for the slot
            loop.call_soon_threadsafe(self._slots.release)
