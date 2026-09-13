
from abc import ABC, abstractmethod
from uuid import UUID

from block.model.block import Block


class BlockRepository(ABC):
    @abstractmethod
    def create_block(self, block: Block) -> None:
        pass

    @abstractmethod
    def remove_block(self, blocker_user_id: UUID, blocked_user_id: UUID) -> None:
        pass
