from pipeline.repository.post_features_repository import PostFeaturesRepository
from post.command.post_commands import DeletePostCommand


class DeletePostUsecase:
    def __init__(self, post_features_repository: PostFeaturesRepository):
        self.post_features_repository = post_features_repository

    def execute(self, command: DeletePostCommand) -> None:
        self.post_features_repository.delete(command.post_id)
