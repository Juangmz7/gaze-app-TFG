from __future__ import annotations

import os
import socket
from collections.abc import Iterator
from pathlib import Path

import pytest


os.environ.setdefault("DATABASE_URL", "postgresql+psycopg://unused:unused@localhost:1/unused")


def _docker_available() -> bool:
    try:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(0.2)
        try:
            return sock.connect_ex("/var/run/docker.sock") == 0
        finally:
            sock.close()
    except OSError:
        return False


@pytest.fixture(scope="session")
def postgres_url() -> Iterator[str]:
    pytest.importorskip("testcontainers.postgres")

    if not _docker_available():
        pytest.skip("Docker is not available for PostgreSQL integration tests")

    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text
    from testcontainers.postgres import PostgresContainer

    image = os.getenv("TEST_POSTGRES_IMAGE", "pgvector/pgvector:pg16")
    with PostgresContainer(image) as postgres:
        url = postgres.get_connection_url().replace("postgresql+psycopg2://", "postgresql+psycopg://")
        os.environ["DATABASE_URL"] = url

        alembic_cfg = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
        command.upgrade(alembic_cfg, "head")

        engine = create_engine(url)
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        engine.dispose()

        yield url


@pytest.fixture()
def db_session_factory(postgres_url: str):
    from sqlalchemy import create_engine, text
    from sqlalchemy.orm import sessionmaker

    engine = create_engine(postgres_url, pool_pre_ping=True)
    factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)

    tables = [
        "processed_events",
        "blocks",
        "follows",
        "collabs",
        "comment_posts",
        "user_post_interactions",
        "user_post_comment_interactions",
        "post_features",
        "post_tag_features",
        "user_features",
        "user_creator_features",
    ]
    with engine.begin() as conn:
        conn.execute(text(f"TRUNCATE {', '.join(tables)} RESTART IDENTITY CASCADE"))

    try:
        yield factory
    finally:
        with engine.begin() as conn:
            conn.execute(text(f"TRUNCATE {', '.join(tables)} RESTART IDENTITY CASCADE"))
        engine.dispose()


@pytest.fixture()
def session_provider(db_session_factory):
    from shared.config.database import SQLAlchemySessionProvider

    return SQLAlchemySessionProvider(db_session_factory)
