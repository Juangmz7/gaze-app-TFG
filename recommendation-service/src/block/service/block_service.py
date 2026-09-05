
import logging
from dataclasses import dataclass
import datetime
from uuid import UUID

from block.model.block import Block
from block.repository.block_repository import BlockRepository
from follow.service.follow_service import FollowService

logger = logging.getLogger(__name__)

class BlockService:
    def __init__(self, block_repository: BlockRepository, follow_service: FollowService):
        self.block_repository = block_repository
        self.follow_service = follow_service

    def create_block(self, blocker_user_id: UUID, blocked_user_id: UUID, created_at: datetime) -> None:
        logger.info("Creating block: blocker=%s, blocked=%s", blocker_user_id, blocked_user_id)
        logger.debug("Block created_at=%s", created_at)
        block = Block(
            blocked_id=blocked_user_id,
            blocker_id=blocker_user_id,
            created_at=created_at
        )
        self.block_repository.create_block(block)
        logger.debug("Removing bidirectional follows after block: blocker=%s, blocked=%s",
                      blocker_user_id, blocked_user_id)
        self.follow_service.remove_follows_between_users(blocker_user_id, blocked_user_id)
        logger.info("Block created successfully: blocker=%s, blocked=%s", blocker_user_id, blocked_user_id)


    def remove_block(self, blocker_user_id: UUID, blocked_user_id: UUID) -> None:
        logger.info("Removing block: blocker=%s, blocked=%s", blocker_user_id, blocked_user_id)
        self.block_repository.remove_block(
            blocker_user_id=blocker_user_id,
            blocked_user_id=blocked_user_id
        )
        logger.info("Block removed successfully: blocker=%s, blocked=%s", blocker_user_id, blocked_user_id)

    