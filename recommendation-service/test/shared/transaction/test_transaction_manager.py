from contextlib import contextmanager

import pytest

from shared.transaction.transaction_manager import (
    TransactionManager,
    after_commit,
    after_commit_scope,
    transaction_scope,
)


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


def test_after_commit_runs_callbacks_in_order_once_the_scope_exits():
    calls = []

    with after_commit_scope():
        after_commit(lambda: calls.append("first"))
        after_commit(lambda: calls.append("second"))
        assert calls == []

    assert calls == ["first", "second"]


def test_after_commit_drops_callbacks_when_the_scope_raises():
    calls = []

    with pytest.raises(RuntimeError):
        with after_commit_scope():
            after_commit(lambda: calls.append("never"))
            raise RuntimeError("rollback")

    assert calls == []


def test_after_commit_runs_right_away_outside_a_scope():
    calls = []

    after_commit(lambda: calls.append("now"))

    assert calls == ["now"]


def test_a_failing_after_commit_callback_does_not_stop_the_others_or_reach_the_caller(caplog):
    calls = []

    def failing():
        raise RuntimeError("boom")

    with after_commit_scope():
        after_commit(failing)
        after_commit(lambda: calls.append("still runs"))

    assert calls == ["still runs"]
    assert "After-commit callback failed" in caplog.text
