from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_interaction_features_repository import PostInteractionFeaturesRepository
from post.command.post_commands import DeletePostCommand


class DeletePostUsecase:
    def __init__(
            self,
            post_features_repository: PostFeaturesRepository,
            post_interaction_features_repository: PostInteractionFeaturesRepository,
    ):
        self.post_features_repository = post_features_repository
        self.post_interaction_features_repository = post_interaction_features_repository

    def execute(self, command: DeletePostCommand) -> None:
        self.post_features_repository.delete(command.post_id)
        self.post_interaction_features_repository.delete(command.post_id)
