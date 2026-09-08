from block.command.block_commands import CreateBlockCommand
from block.usecase.create_block_usecase import CreateBlockUsecase
from rabbitmq.event.user.user_events import UserBlockCreatedEvent


class BlockCreatedEventHandler:
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
