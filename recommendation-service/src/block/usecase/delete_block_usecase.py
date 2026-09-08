from block.command.block_commands import DeleteBlockCommand
from block.service.block_service import BlockService


class DeleteBlockUsecase:
    def __init__(self, block_service: BlockService):
        self.block_service = block_service

    def execute(self, command: DeleteBlockCommand) -> None:
        self.block_service.remove_block(
            blocker_user_id=command.blocker_user_id,
            blocked_user_id=command.blocked_user_id,
        )
