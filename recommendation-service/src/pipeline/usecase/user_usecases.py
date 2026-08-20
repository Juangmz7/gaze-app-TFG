from pipeline.commands.user_commands import (
    DeleteUserCommand,
    RegisterUserCommand,
    UpdateUserCommand,
)


class RegisterUserUsecase:
    def execute(self, command: RegisterUserCommand) -> None:
        pass


class UpdateUserUsecase:
    def execute(self, command: UpdateUserCommand) -> None:
        pass


class DeleteUserUsecase:
    def execute(self, command: DeleteUserCommand) -> None:
        pass
