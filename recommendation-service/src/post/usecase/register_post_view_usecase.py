
import logging

from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository
from post.command.post_commands import RegisterPostViewCommand

logger = logging.getLogger(__name__)


class RegisterPostViewUsecase:
    def __init__(
            self,
            user_creator_features_repostory: UserCreatorFeaturesRepository,
            post_tags_features_repository: PostTagFeaturesRepository,
            post_features_repository: PostFeaturesRepository
    ):
        self.user_creator_features_repostory = user_creator_features_repostory
        self.post_tags_features_repository = post_tags_features_repository
        self.post_features_repository = post_features_repository

    def execute(self, command: RegisterPostViewCommand) -> None:
        logger.info(
            "Post view feature updates are temporarily disabled: post_id=%s, user_id=%s",
            command.post_id,
            command.user_id,
        )
