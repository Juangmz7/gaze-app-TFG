from pipeline.commands.user_commands import (
    DeleteUserCommand,
    RegisterUserCommand,
    UpdateUserCommand,
)
from pipeline.usecase.user_usecases import (
    DeleteUserUsecase,
    RegisterUserUsecase,
    UpdateUserUsecase,
)
from rabbitmq.event.user.user_events import (
    UserDeletedEvent,
    UserRegisteredEvent,
    UserUpdatedEvent,
)


class UserRegisteredHandler:
    def __init__(self, register_user_usecase: RegisterUserUsecase):
        self.register_user_usecase = register_user_usecase

    async def handle(self, event: UserRegisteredEvent) -> str:
        command = RegisterUserCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            user_id=event.userId,
            username=event.username,
            email=event.email,
            bio=event.bio,
        )
        self.register_user_usecase.execute(command)
        return event.__class__.__name__


class UserUpdatedHandler:
    def __init__(self, update_user_usecase: UpdateUserUsecase):
        self.update_user_usecase = update_user_usecase

    async def handle(self, event: UserUpdatedEvent) -> str:
        command = UpdateUserCommand(
            event_id=event.id,
            correlation_id=event.correlationId,
            occurred_at=event.occurredAt,
            user_id=event.userId,
            username=event.username,
            email=event.email,
            bio=event.bio,
            picture_url=event.pictureUrl,
            account_status=event.accountStatus,
            created_at=event.createdAt,
            updated_at=event.updatedAt,
        )
        self.update_user_usecase.execute(command)
        return event.__class__.__name__


class UserDeletedHandler:
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
