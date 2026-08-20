from pipeline.commands.post_commands import (
    BanPostCommand,
    CreatePostCommand,
    DeletePostCommand,
    FeedExhaustedCommand,
    UpdatePostCommand,
)


class CreatePostUsecase:
    def execute(self, command: CreatePostCommand) -> None:
        pass


class UpdatePostUsecase:
    def execute(self, command: UpdatePostCommand) -> None:
        pass


class DeletePostUsecase:
    def execute(self, command: DeletePostCommand) -> None:
        pass


class BanPostUsecase:
    def execute(self, command: BanPostCommand) -> None:
        pass


class FeedExhaustedUsecase:
    def execute(self, command: FeedExhaustedCommand) -> None:
        pass
