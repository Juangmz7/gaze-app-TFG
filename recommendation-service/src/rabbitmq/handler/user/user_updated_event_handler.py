from rabbitmq.event.user.user_events import UserUpdatedEvent
from user.command.user_commands import UpdateUserCommand
from user.usecase.update_user_usecase import UpdateUserUsecase


class UserUpdatedEventHandler:
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
