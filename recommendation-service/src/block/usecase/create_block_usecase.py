from block.command.block_commands import CreateBlockCommand
from block.service.block_service import BlockService


class CreateBlockUsecase:
    def __init__(self, block_service: BlockService):
        self.block_service = block_service

    def execute(self, command: CreateBlockCommand) -> None:
        self.block_service.create_block(
            blocker_user_id=command.blocker_user_id,
            blocked_user_id=command.blocked_user_id,
            created_at=command.occurred_at,
        )
