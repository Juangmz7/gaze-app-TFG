from block.commands import CreateBlockCommand, DeleteBlockCommand
from block.usecase import CreateBlockUsecase, DeleteBlockUsecase
from rabbitmq.event.user.user_events import (
    UserBlockCreatedEvent,
    UserBlockDeletedEvent,
)


class BlockCreatedHandler:
    def __init__(self, create_block_usecase: CreateBlockUsecase):
        self.create_block_usecase = create_block_usecase

    async def handle(self, event: UserBlockCreatedEvent) -> str:
        command = CreateBlockCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            blocker_user_id=event.blockerUserId,
            blocked_user_id=event.blockedUserId,
        )
        self.create_block_usecase.execute(command)
        return event.__class__.__name__


class BlockDeletedHandler:
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
