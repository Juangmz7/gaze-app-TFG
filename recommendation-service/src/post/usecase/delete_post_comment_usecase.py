import logging

from pipeline.config.constants import (
    COMMENT_ACCUMULATION_DECAY,
    POST_COMMENT_DELETED_PENALISATION_WEIGHT,
    POST_COMMENT_WEIGHT,
)
from post.command.post_commands import DeletePostCommentCommand
from post.model.user_post_interactions import UserPostInteractions
from post.repository.comment_post_repository import CommentPostRepository
from post.repository.user_post_interactions_repository import UserPostInteractionsRepository
from post.usecase.post_interaction_updater import PostInteractionUpdater
from shared.enum.interaction_metric import InteractionMetric
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate

logger = logging.getLogger(__name__)


class DeletePostCommentInteractionUsecase:
    def __init__(
            self,
            post_interaction_updater: PostInteractionUpdater,
            user_post_interactions_repository: UserPostInteractionsRepository,
            comment_post_repository: CommentPostRepository,
    ):
        self.post_interaction_updater = post_interaction_updater
        self.user_post_interactions_repository = user_post_interactions_repository
        self.comment_post_repository = comment_post_repository

    def execute(self, command: DeletePostCommentCommand) -> None:
        interaction = self._get_or_create_interaction(command.post_id, command.user_id)
        if interaction.comment_count <= 0:
            logger.info(
                "Ignoring comment deletion with empty interaction counter: post_id=%s, user_id=%s",
                command.post_id,
                command.user_id,
            )
            self.comment_post_repository.delete_comment_post(command.comment_id)
            return

        weight = -(
            POST_COMMENT_WEIGHT
            * (COMMENT_ACCUMULATION_DECAY ** (interaction.comment_count - 1))
            * POST_COMMENT_DELETED_PENALISATION_WEIGHT
        )
        self.post_interaction_updater.apply(
            post_id=command.post_id,
            user_id=command.user_id,
            metric_updates=[
                InteractionMetricUpdate(
                    metric=InteractionMetric.COMMENTS,
                    raw_delta=-1,
                    decayed_delta=-1,
                )
            ],
            embedding_weight=weight,
            occurred_at=command.occurred_at,
        )
        interaction.comment_count -= 1
        self.user_post_interactions_repository.save(interaction)
        self.comment_post_repository.delete_comment_post(command.comment_id)

    def _get_or_create_interaction(self, post_id, user_id) -> UserPostInteractions:
        return (
            self.user_post_interactions_repository.get(post_id, user_id)
            or UserPostInteractions(post_id=post_id, user_id=user_id)
        )


DeletePostCommentUsecase = DeletePostCommentInteractionUsecase
