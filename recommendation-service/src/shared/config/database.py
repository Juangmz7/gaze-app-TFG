import os
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker


DATABASE_URL = os.getenv("DATABASE_URL")

engine = create_engine(
    DATABASE_URL,
    pool_pre_ping=True,
)

SessionFactory = sessionmaker(
    bind=engine,
    autoflush=False,
    expire_on_commit=False,
)

_current_session: ContextVar[Session | None] = ContextVar(
    "current_session",
    default=None,
)


class SQLAlchemySessionProvider:

    def __init__(
        self,
        session_factory: sessionmaker[Session] = SessionFactory,
    ) -> None:
        self.session_factory = session_factory

    @contextmanager
    def session(self) -> Iterator[Session]:
        current_session = _current_session.get()

        if current_session is not None:
            yield current_session
            return

        with self.session_factory() as session:
            yield session


class SQLAlchemyTransactionManager:

    def __init__(
        self,
        session_factory: sessionmaker[Session] = SessionFactory,
    ) -> None:
        self.session_factory = session_factory

    @contextmanager
    def transaction(self) -> Iterator[Session]:
        current_session = _current_session.get()

        if current_session is not None:
            yield current_session
            return

        with self.session_factory() as session:
            token = _current_session.set(session)

            try:
                with session.begin():
                    yield session
            finally:
                _current_session.reset(token)