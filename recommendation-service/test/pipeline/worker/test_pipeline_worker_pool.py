import asyncio
import threading
from contextvars import ContextVar

import pytest

from pipeline.worker.pipeline_worker_pool import PipelineWorkerPool, PipelineWorkerPoolSaturatedError

pytestmark = pytest.mark.unit

request_scoped: ContextVar[str | None] = ContextVar("request_scoped", default=None)


def blocked_function(release: threading.Event, started: threading.Event | None = None):
    def function():
        if started is not None:
            started.set()
        release.wait(timeout=5)
        return "done"

    return function


async def wait_for_event(event: threading.Event) -> None:
    await asyncio.to_thread(event.wait, 5)


async def test_should_run_off_the_event_loop_thread():
    pool = PipelineWorkerPool(1)

    worker_thread = await pool.run(threading.get_ident)

    assert worker_thread != threading.get_ident()
    await pool.shutdown()


async def test_should_keep_the_event_loop_running_during_a_run():
    pool = PipelineWorkerPool(1)
    release = threading.Event()
    run = asyncio.create_task(pool.run(blocked_function(release)))

    # The loop keeps scheduling this coroutine while the function is blocked
    await asyncio.sleep(0.01)
    assert not run.done()

    release.set()
    assert await run == "done"
    await pool.shutdown()


async def test_should_run_with_the_context_of_the_caller():
    pool = PipelineWorkerPool(1)
    request_scoped.set("correlation")

    assert await pool.run(request_scoped.get) == "correlation"
    await pool.shutdown()


async def test_should_propagate_the_exception_of_the_function_and_free_the_slot():
    pool = PipelineWorkerPool(1)

    def fail():
        raise RuntimeError("pipeline failed")

    with pytest.raises(RuntimeError, match="pipeline failed"):
        await pool.try_run(fail)

    assert await pool.try_run(lambda: "next") == "next"
    await pool.shutdown()


async def test_try_run_should_fail_right_away_when_every_worker_is_busy_and_not_run_the_function():
    pool = PipelineWorkerPool(1)
    release, started = threading.Event(), threading.Event()
    running = asyncio.create_task(pool.try_run(blocked_function(release, started)))
    await wait_for_event(started)
    calls = []

    with pytest.raises(PipelineWorkerPoolSaturatedError):
        await pool.try_run(lambda: calls.append("ran"))

    assert calls == []
    release.set()
    await running
    await pool.shutdown()


async def test_run_should_wait_for_a_free_worker():
    pool = PipelineWorkerPool(1)
    release, started = threading.Event(), threading.Event()
    running = asyncio.create_task(pool.run(blocked_function(release, started)))
    await wait_for_event(started)

    waiting = asyncio.create_task(pool.run(lambda: "second"))
    await asyncio.sleep(0.05)
    assert not waiting.done()

    release.set()
    assert await waiting == "second"
    await running
    await pool.shutdown()


async def test_should_hold_the_slot_until_a_cancelled_run_really_ends():
    # Arrange: the caller goes away (client deadline), the thread cannot be stopped
    pool = PipelineWorkerPool(1)
    release, started = threading.Event(), threading.Event()
    running = asyncio.create_task(pool.try_run(blocked_function(release, started)))
    await wait_for_event(started)

    # Act
    running.cancel()
    with pytest.raises(asyncio.CancelledError):
        await running

    # Assert: still bounded while the thread runs, free once it ended
    with pytest.raises(PipelineWorkerPoolSaturatedError):
        await pool.try_run(lambda: "too many")

    release.set()
    await pool.shutdown()
    await asyncio.sleep(0)
    assert await asyncio.wait_for(pool._slots.acquire(), timeout=1)


def test_should_reject_a_pool_without_workers():
    with pytest.raises(ValueError):
        PipelineWorkerPool(0)
