from pipeline.repository.post_features_repository import PostFeaturesRepository
from post.command.post_commands import DeletePostCollabCommand
from post.repository.collab_repository import CollabRepository


class DeletePostCollabUsecase:
    def __init__(
            self,
            collab_repository: CollabRepository,
            post_features_repository: PostFeaturesRepository,
    ):
        self.collab_repository = collab_repository
        self.post_features_repository = post_features_repository

    def execute(self, command: DeletePostCollabCommand) -> None:
        post_ids = self.collab_repository.find_posts_id_by_collab_id(command.collab_id)
        self.collab_repository.delete(command.collab_id)
        self.post_features_repository.clear_collab_for_posts(post_ids)
