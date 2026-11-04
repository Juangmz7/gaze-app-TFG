from contextlib import contextmanager

import pytest

from shared.transaction.transaction_manager import TransactionManager, transaction_scope


pytestmark = pytest.mark.unit


class RecordingTransactionManager(TransactionManager):
    def __init__(self):
        self.events = []

    @contextmanager
    def transaction(self):
        self.events.append("begin")
        try:
            yield
            self.events.append("commit")
        except Exception:
            self.events.append("rollback")
            raise


def test_transaction_scope_runs_inside_the_managers_transaction():
    manager = RecordingTransactionManager()

    with transaction_scope(manager):
        manager.events.append("work")

    assert manager.events == ["begin", "work", "commit"]


def test_transaction_scope_propagates_errors_so_the_manager_rolls_back():
    manager = RecordingTransactionManager()

    with pytest.raises(RuntimeError):
        with transaction_scope(manager):
            raise RuntimeError("boom")

    assert manager.events == ["begin", "rollback"]


def test_transaction_scope_is_a_no_op_without_a_manager():
    with transaction_scope(None):
        pass
