import logging

from pipeline.config.constants import (
    POST_COMMENT_LIKE_WEIGHT,
    POST_COMMENT_UNLIKE_PENALISATION_WEIGHT,
)
from post.command.post_commands import DeletePostCommentLikeCommand
from post.model.user_post_comment_interaction import UserPostCommentInteraction
from post.repository.comment_post_repository import CommentPostRepository
from post.repository.user_post_comment_interaction_repository import (
    UserPostCommentInteractionRepository,
)
from post.usecase.post_interaction_updater import PostInteractionUpdater

logger = logging.getLogger(__name__)


class DeletePostCommentLikeInteractionUsecase:
    def __init__(
            self,
            post_interaction_updater: PostInteractionUpdater,
            user_post_comment_interaction_repository: UserPostCommentInteractionRepository,
            comment_post_repository: CommentPostRepository,
    ):
        self.post_interaction_updater = post_interaction_updater
        self.user_post_comment_interaction_repository = user_post_comment_interaction_repository
        self.comment_post_repository = comment_post_repository

    def execute(self, command: DeletePostCommentLikeCommand) -> None:
        interaction = self._get_or_create_interaction(command.comment_id, command.user_id)
        if interaction.ever_unliked:
            logger.info(
                "Ignoring duplicate post comment-unlike interaction: comment_id=%s, user_id=%s",
                command.comment_id,
                command.user_id,
            )
            return

        post_id = self.comment_post_repository.find_post_id_by_comment_id(command.comment_id)
        if post_id is None:
            logger.warning("Post id not found for comment-like deletion: comment_id=%s", command.comment_id)
            return

        self.post_interaction_updater.apply(
            post_id=post_id,
            user_id=command.user_id,
            metric_name="commentsLikes",
            raw_delta=-1,
            embedding_weight=-(POST_COMMENT_LIKE_WEIGHT * POST_COMMENT_UNLIKE_PENALISATION_WEIGHT),
            source=command.source,
        )
        interaction.ever_unliked = True
        self.user_post_comment_interaction_repository.save(interaction)

    def _get_or_create_interaction(self, comment_id, user_id) -> UserPostCommentInteraction:
        return (
            self.user_post_comment_interaction_repository.get(comment_id, user_id)
            or UserPostCommentInteraction(comment_id=comment_id, user_id=user_id)
        )


DeletePostCommentLikeUsecase = DeletePostCommentLikeInteractionUsecase
