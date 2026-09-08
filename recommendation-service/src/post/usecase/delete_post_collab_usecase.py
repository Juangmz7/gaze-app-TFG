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
        post_id = self.collab_repository.find_post_id_by_collab_id(command.collab_id)
        self.collab_repository.delete(command.collab_id)
        if post_id is not None:
            self.post_features_repository.update_post_collab(
                post_id=post_id,
                collab_id=None,
                collab_title=None,
            )
