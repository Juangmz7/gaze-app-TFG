from uuid import UUID

from sqlalchemy import delete, insert, update

from block.entity.block_entity import BlockRecord
from block.model.block import Block
from block.repository.block_repository import BlockRepository
from shared.config.database import SQLAlchemySessionProvider


class SqlAlchemyBlockRepository(BlockRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def create_block(self, block: Block) -> None:
        values = {
            "blocker_id": block.blocker_id,
            "blocked_id": block.blocked_id,
            "created_at": block.created_at,
        }
        with self.session_provider.session() as session:
            result = session.execute(
                update(BlockRecord)
                .where(
                    BlockRecord.blocker_id == block.blocker_id,
                    BlockRecord.blocked_id == block.blocked_id,
                )
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(BlockRecord).values(**values))

    def remove_block(self, blocker_user_id: UUID, blocked_user_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(
                delete(BlockRecord).where(
                    BlockRecord.blocker_id == blocker_user_id,
                    BlockRecord.blocked_id == blocked_user_id,
                )
            )
