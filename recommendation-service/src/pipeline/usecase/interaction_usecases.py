from pipeline.commands.interaction_commands import (
    CreatePostCollabRequestCommand,
    CreatePostCommentCommand,
    CreatePostCommentLikeCommand,
    CreatePostLikeCommand,
    CreatePostShareCommand,
    CreatePostViewCommand,
    DeletePostCollabRequestCommand,
    DeletePostCommentCommand,
    DeletePostCommentLikeCommand,
    DeletePostLikeCommand,
    DeletePostShareCommand,
)


class CreatePostShareUsecase:
    def execute(self, command: CreatePostShareCommand) -> None:
        pass


class DeletePostShareUsecase:
    def execute(self, command: DeletePostShareCommand) -> None:
        pass


class CreatePostCollabRequestUsecase:
    def execute(self, command: CreatePostCollabRequestCommand) -> None:
        pass


class DeletePostCollabRequestUsecase:
    def execute(self, command: DeletePostCollabRequestCommand) -> None:
        pass


class CreatePostCommentUsecase:
    def execute(self, command: CreatePostCommentCommand) -> None:
        pass


class DeletePostCommentUsecase:
    def execute(self, command: DeletePostCommentCommand) -> None:
        pass


class CreatePostCommentLikeUsecase:
    def execute(self, command: CreatePostCommentLikeCommand) -> None:
        pass


class DeletePostCommentLikeUsecase:
    def execute(self, command: DeletePostCommentLikeCommand) -> None:
        pass


class CreatePostLikeUsecase:
    def execute(self, command: CreatePostLikeCommand) -> None:
        pass


class DeletePostLikeUsecase:
    def execute(self, command: DeletePostLikeCommand) -> None:
        pass


class CreatePostViewUsecase:
    def execute(self, command: CreatePostViewCommand) -> None:
        pass
