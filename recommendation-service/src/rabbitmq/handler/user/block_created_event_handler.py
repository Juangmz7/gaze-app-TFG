from block.command.block_commands import CreateBlockCommand
from block.usecase.create_block_usecase import CreateBlockUsecase
from rabbitmq.event.user.user_events import UserBlockCreatedEvent


class BlockCreatedEventHandler:
    @staticmethod
    async def handle(event: UserBlockCreatedEvent) -> str:
        command = CreateBlockCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            blocker_user_id=event.blockerUserId,
            blocked_user_id=event.blockedUserId,
        )
        CreateBlockUsecase.execute(command)
        return event.__class__.__name__
