import logging
from abc import ABC, abstractmethod
from collections.abc import Callable, Iterator
from contextlib import AbstractContextManager, contextmanager, nullcontext
from contextvars import ContextVar

logger = logging.getLogger(__name__)

# Callbacks registered with after_commit() in the current outermost transaction
_after_commit_callbacks: ContextVar[list[Callable[[], None]] | None] = ContextVar(
    "after_commit_callbacks",
    default=None,
)


class TransactionManager(ABC):
    """Unit-of-work boundary owned by use cases and listeners: every repository
    call made inside transaction() commits or rolls back together (e.g. a state
    change and the outbox event it produces). A nested transaction() joins the
    outer one. Callbacks registered with after_commit() inside it run once it
    has committed (implementations wrap their outermost transaction in
    after_commit_scope())."""

    @abstractmethod
    def transaction(self) -> AbstractContextManager[None]:
        pass


def transaction_scope(transaction_manager: TransactionManager | None) -> AbstractContextManager[None]:
    """transaction_manager.transaction(), or a no-op scope when none is wired
    (unit tests that build the use case with in-memory collaborators)."""
    if transaction_manager is None:
        return nullcontext()
    return transaction_manager.transaction()


def after_commit(callback: Callable[[], None]) -> None:
    """Runs callback once the current transaction has committed, and never if it
    rolls back. Outside a transaction it runs right away, because the write it
    follows is already committed. A failing callback is logged, not raised: the
    transaction it follows has already committed."""
    callbacks = _after_commit_callbacks.get()
    if callbacks is None:
        _run_after_commit(callback)
    else:
        callbacks.append(callback)


@contextmanager
def after_commit_scope() -> Iterator[None]:
    """For TransactionManager implementations: wrap the outermost transaction
    (commit included) so the after_commit() callbacks registered inside run,
    in order, once the block exits without error; they are dropped if it raises."""
    callbacks: list[Callable[[], None]] = []
    token = _after_commit_callbacks.set(callbacks)
    try:
        yield
    finally:
        _after_commit_callbacks.reset(token)
    # Reached only when the block (and so the commit) succeeded
    for callback in callbacks:
        _run_after_commit(callback)


def _run_after_commit(callback: Callable[[], None]) -> None:
    try:
        callback()
    except Exception:
        logger.exception("After-commit callback failed")
