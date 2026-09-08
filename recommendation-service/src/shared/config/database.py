import os
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker


DEFAULT_DATABASE_URL = "sqlite:///recommendation-service.db"
RAW_DATABASE_URL = os.getenv(
    "RECOMMENDATION_DATABASE_URL",
    os.getenv("DATABASE_URL", DEFAULT_DATABASE_URL),
)


def _normalize_database_url(database_url: str) -> str:
    if database_url.startswith("postgresql://"):
        return database_url.replace("postgresql://", "postgresql+psycopg://", 1)
    if database_url.startswith("postgres://"):
        return database_url.replace("postgres://", "postgresql+psycopg://", 1)
    return database_url


DATABASE_URL = _normalize_database_url(RAW_DATABASE_URL)


def _connect_args(database_url: str) -> dict[str, object]:
    if database_url.startswith("sqlite"):
        return {"check_same_thread": False}
    return {}


engine = create_engine(
    DATABASE_URL,
    pool_pre_ping=True,
    connect_args=_connect_args(DATABASE_URL),
)

SessionFactory = sessionmaker(
    bind=engine,
    autoflush=False,
    expire_on_commit=False,
)

_current_session: ContextVar[Session | None] = ContextVar("current_session", default=None)


class SQLAlchemySessionProvider:
    def __init__(self, session_factory: sessionmaker[Session] = SessionFactory):
        self.session_factory = session_factory

    @contextmanager
    def session(self) -> Iterator[Session]:
        current_session = _current_session.get()
        if current_session is not None:
            yield current_session
            return

        with self.session_factory() as session:
            with session.begin():
                yield session


class SQLAlchemyTransactionManager:
    def __init__(self, session_factory: sessionmaker[Session] = SessionFactory):
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


def initialize_database(db_engine: Engine = engine) -> None:
    from block.entity import block_entity
    from follow.entity import follow_entity
    from pipeline.entity.post import post_features_entity, post_tag_features_entity
    from pipeline.entity.user import user_creator_features_entity, user_features_entity
    from post.entity import (
        collab_entity,
        comment_post_entity,
        user_post_comment_interaction_entity,
        user_post_interactions_entity,
    )
    from shared.entity import processed_event_entity
    from shared.entity.base import Base

    _ = (
        block_entity,
        follow_entity,
        post_features_entity,
        post_tag_features_entity,
        user_creator_features_entity,
        user_features_entity,
        collab_entity,
        comment_post_entity,
        user_post_comment_interaction_entity,
        user_post_interactions_entity,
        processed_event_entity,
    )
    Base.metadata.create_all(db_engine)
