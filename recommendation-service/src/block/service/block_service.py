

from dataclasses import dataclass
import datetime
from uuid import UUID

from block.model.block import Block
from block.repository.block_repository import BlockRepository
from follow.service.follow_service import FollowService

class BlockService:
    def __init__(self, block_repository: BlockRepository, follow_service: FollowService):
        self.block_repository = block_repository
        self.follow_service = follow_service

    def create_block(self, blocker_user_id: UUID, blocked_user_id: UUID, created_at: datetime) -> None:
        block = Block(
            blocked_id=blocked_user_id,
            blocker_id=blocker_user_id,
            created_at=created_at
        )
        self.block_repository.create_block(block)
        self.follow_service.remove_follows_between_users(blocker_user_id, blocked_user_id)


    def remove_block(self, blocker_user_id: UUID, blocked_user_id: UUID) -> None:
        self.block_repository.remove_block(
            blocker_user_id=blocker_user_id,
            blocked_user_id=blocked_user_id
        )

    