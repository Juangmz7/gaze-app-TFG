from abc import ABC, abstractmethod
from contextlib import AbstractContextManager, nullcontext


class TransactionManager(ABC):
    """Unit-of-work boundary owned by use cases and listeners: every repository
    call made inside transaction() commits or rolls back together (e.g. a state
    change and the outbox event it produces). A nested transaction() joins the
    outer one."""

    @abstractmethod
    def transaction(self) -> AbstractContextManager[None]:
        pass


def transaction_scope(transaction_manager: TransactionManager | None) -> AbstractContextManager[None]:
    """transaction_manager.transaction(), or a no-op scope when none is wired
    (unit tests that build the use case with in-memory collaborators)."""
    if transaction_manager is None:
        return nullcontext()
    return transaction_manager.transaction()
