
from dataclasses import dataclass
from uuid import UUID

from block.model.block import Block

class BlockRepository:
    def create_block(self, block: Block) -> None:
        # Implement the logic to create a block in the database
        pass

    def remove_block(self, blocker_user_id: UUID, blocked_user_id: UUID) -> None:
        # Implement the logic to remove a block from the database
        pass