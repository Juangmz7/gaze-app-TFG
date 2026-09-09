import logging

from pipeline.config.constants import (
    COLLAB_REQUEST_DELETED_PENALISATION_WEIGHT,
    COLLAB_REQUEST_WEIGHT,
)
from post.command.post_commands import DeletePostCollabRequestCommand
from post.model.user_post_interactions import UserPostInteractions
from post.repository.collab_repository import CollabRepository
from post.repository.user_post_interactions_repository import UserPostInteractionsRepository
from post.usecase.post_interaction_updater import PostInteractionUpdater
from shared.enum.interaction_metric import InteractionMetric
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate

logger = logging.getLogger(__name__)


class DeletePostCollabRequestInteractionUsecase:
    def __init__(
            self,
            post_interaction_updater: PostInteractionUpdater,
            user_post_interactions_repository: UserPostInteractionsRepository,
            collab_repository: CollabRepository,
    ):
        self.post_interaction_updater = post_interaction_updater
        self.user_post_interactions_repository = user_post_interactions_repository
        self.collab_repository = collab_repository

    def execute(self, command: DeletePostCollabRequestCommand) -> None:
        post_id = self.collab_repository.find_post_id_by_collab_id(command.collab_id)
        if post_id is None:
            logger.warning(
                "Post id not found for collab request deletion: collab_id=%s",
                command.collab_id,
            )
            return

        interaction = self._get_or_create_interaction(post_id, command.user_id)
        if interaction.ever_request_collab_deleted:
            logger.info(
                "Ignoring duplicate collab request deletion interaction: post_id=%s, user_id=%s",
                post_id,
                command.user_id,
            )
            return

        self.post_interaction_updater.apply(
            post_id=post_id,
            user_id=command.user_id,
            metric_updates=[
                InteractionMetricUpdate(
                    metric=InteractionMetric.COLLAB_REQUESTS,
                    raw_delta=-1,
                    decayed_delta=-1,
                )
            ],
            embedding_weight=-(COLLAB_REQUEST_WEIGHT * COLLAB_REQUEST_DELETED_PENALISATION_WEIGHT),
            occurred_at=command.occurred_at,
        )
        interaction.ever_request_collab_deleted = True
        self.user_post_interactions_repository.save(interaction)

    def _get_or_create_interaction(self, post_id, user_id) -> UserPostInteractions:
        return (
            self.user_post_interactions_repository.get(post_id, user_id)
            or UserPostInteractions(post_id=post_id, user_id=user_id)
        )


DeletePostCollabRequestUsecase = DeletePostCollabRequestInteractionUsecase
