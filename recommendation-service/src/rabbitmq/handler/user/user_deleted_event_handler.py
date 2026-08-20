from rabbitmq.event.user.user_events import UserDeletedEvent
from user.command.user_commands import DeleteUserCommand
from user.usecase.delete_user_usecase import DeleteUserUsecase


class UserDeletedEventHandler:
    async def handle(event: UserDeletedEvent) -> str:
        command = DeleteUserCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            user_id=event.userId,
        )
        DeleteUserUsecase.execute(command)
        return event.__class__.__name__
