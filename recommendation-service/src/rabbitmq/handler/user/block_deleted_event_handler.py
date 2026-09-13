from block.command.block_commands import DeleteBlockCommand
from block.usecase.delete_block_usecase import DeleteBlockUsecase
from rabbitmq.event.user.user_events import UserBlockDeletedEvent


class BlockDeletedEventHandler:
    def __init__(self, delete_block_usecase: DeleteBlockUsecase):
        self.delete_block_usecase = delete_block_usecase

    async def handle(self, event: UserBlockDeletedEvent) -> str:
        command = DeleteBlockCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            blocker_user_id=event.blockerUserId,
            blocked_user_id=event.blockedUserId,
        )
        self.delete_block_usecase.execute(command)
        return event.__class__.__name__
