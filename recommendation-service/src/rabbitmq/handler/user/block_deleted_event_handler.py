from block.command.block_commands import DeleteBlockCommand
from block.usecase.delete_block_usecase import DeleteBlockUsecase
from rabbitmq.event.user.user_events import UserBlockDeletedEvent


class BlockDeletedEventHandler:
    async def handle(event: UserBlockDeletedEvent) -> str:
        command = DeleteBlockCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            blocker_user_id=event.blockerUserId,
            blocked_user_id=event.blockedUserId,
        )
        DeleteBlockUsecase.execute(command)
        return event.__class__.__name__
