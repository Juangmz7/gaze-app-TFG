from rabbitmq.event.user.user_events import UserRegisteredEvent
from user.command.user_commands import RegisterUserCommand
from user.usecase.register_user_usecase import RegisterUserUsecase


class UserRegisteredEventHandler:
    @staticmethod
    async def handle(event: UserRegisteredEvent) -> str:
        command = RegisterUserCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            user_id=event.userId,
            username=event.username,
            email=event.email,
            bio=event.bio,
        )
        RegisterUserUsecase.execute(command)
        return event.__class__.__name__
