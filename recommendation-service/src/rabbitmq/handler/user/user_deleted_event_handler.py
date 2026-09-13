from rabbitmq.event.user.user_events import UserDeletedEvent
from user.command.user_commands import DeleteUserCommand
from user.usecase.delete_user_usecase import DeleteUserUsecase


class UserDeletedEventHandler:
    def __init__(self, delete_user_usecase: DeleteUserUsecase):
        self.delete_user_usecase = delete_user_usecase

    async def handle(self, event: UserDeletedEvent) -> str:
        command = DeleteUserCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            user_id=event.userId,
        )
        self.delete_user_usecase.execute(command)
        return event.__class__.__name__
