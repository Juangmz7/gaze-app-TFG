import logging

from pipeline.config.constants import COLLAB_REQUEST_WEIGHT
from post.command.post_commands import CreatePostCollabRequestCommand
from post.model.user_post_interactions import UserPostInteractions
from post.repository.collab_repository import CollabRepository
from post.repository.user_post_interactions_repository import UserPostInteractionsRepository
from post.usecase.post_interaction_updater import PostInteractionUpdater
from shared.enum.interaction_metric import InteractionMetric
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate

logger = logging.getLogger(__name__)


class CreatePostCollabRequestInteractionUsecase:
    def __init__(
            self,
            post_interaction_updater: PostInteractionUpdater,
            user_post_interactions_repository: UserPostInteractionsRepository,
            collab_repository: CollabRepository,
    ):
        self.post_interaction_updater = post_interaction_updater
        self.user_post_interactions_repository = user_post_interactions_repository
        self.collab_repository = collab_repository

    def execute(self, command: CreatePostCollabRequestCommand) -> None:
        post_ids = self.collab_repository.find_posts_id_by_collab_id(command.collab_id)
        if not post_ids:
            logger.warning("Post id not found for collab request: collab_id=%s", command.collab_id)
            return

        for post_id in post_ids:
            interaction = self._get_or_create_interaction(post_id, command.user_id)
            if interaction.ever_requested_collab:
                logger.info(
                    "Ignoring duplicate collab request interaction: post_id=%s, user_id=%s",
                    post_id,
                    command.user_id,
                )
                continue

            self.post_interaction_updater.apply(
                post_id=post_id,
                user_id=command.user_id,
                metric_updates=[
                    InteractionMetricUpdate(
                        metric=InteractionMetric.COLLAB_REQUESTS,
                        raw_delta=1,
                        decayed_delta=1,
                    )
                ],
                embedding_weight=COLLAB_REQUEST_WEIGHT,
                occurred_at=command.occurred_at,
            )
            interaction.ever_requested_collab = True
            self.user_post_interactions_repository.save(interaction)

    def _get_or_create_interaction(self, post_id, user_id) -> UserPostInteractions:
        return (
            self.user_post_interactions_repository.get(post_id, user_id)
            or UserPostInteractions(post_id=post_id, user_id=user_id)
        )


CreatePostCollabRequestUsecase = CreatePostCollabRequestInteractionUsecase
