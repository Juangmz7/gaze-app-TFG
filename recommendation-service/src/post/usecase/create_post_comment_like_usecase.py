import logging

from pipeline.config.constants import POST_COMMENT_LIKE_WEIGHT
from post.command.post_commands import CreatePostCommentLikeCommand
from post.model.user_post_comment_interaction import UserPostCommentInteraction
from post.repository.user_post_comment_interaction_repository import (
    UserPostCommentInteractionRepository,
)
from post.usecase.post_interaction_updater import PostInteractionUpdater

logger = logging.getLogger(__name__)


class CreatePostCommentLikeInteractionUsecase:
    def __init__(
            self,
            post_interaction_updater: PostInteractionUpdater,
            user_post_comment_interaction_repository: UserPostCommentInteractionRepository,
    ):
        self.post_interaction_updater = post_interaction_updater
        self.user_post_comment_interaction_repository = user_post_comment_interaction_repository

    def execute(self, command: CreatePostCommentLikeCommand) -> None:
        interaction = self._get_or_create_interaction(command.comment_id, command.user_id)
        if interaction.ever_liked:
            logger.info(
                "Ignoring duplicate post comment-like interaction: comment_id=%s, user_id=%s",
                command.comment_id,
                command.user_id,
            )
            return

        self.post_interaction_updater.apply(
            post_id=command.post_id,
            user_id=command.user_id,
            metric_name="commentsLikes",
            raw_delta=1,
            embedding_weight=POST_COMMENT_LIKE_WEIGHT,
            source=command.source,
        )
        interaction.ever_liked = True
        self.user_post_comment_interaction_repository.save(interaction)

    def _get_or_create_interaction(self, comment_id, user_id) -> UserPostCommentInteraction:
        return (
            self.user_post_comment_interaction_repository.get(comment_id, user_id)
            or UserPostCommentInteraction(comment_id=comment_id, user_id=user_id)
        )


CreatePostCommentLikeUsecase = CreatePostCommentLikeInteractionUsecase
