import logging

from pipeline.config.constants import POST_SHARE_WEIGHT, POST_UNSHARE_PENALISATION_WEIGHT
from post.command.post_commands import DeletePostShareCommand
from post.model.user_post_interactions import UserPostInteractions
from post.repository.user_post_interactions_repository import UserPostInteractionsRepository
from post.usecase.post_interaction_updater import PostInteractionUpdater
from shared.enum.interaction_metric import InteractionMetric
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate

logger = logging.getLogger(__name__)


class DeletePostShareInteractionUsecase:
    def __init__(
            self,
            post_interaction_updater: PostInteractionUpdater,
            user_post_interactions_repository: UserPostInteractionsRepository,
    ):
        self.post_interaction_updater = post_interaction_updater
        self.user_post_interactions_repository = user_post_interactions_repository

    def execute(self, command: DeletePostShareCommand) -> None:
        interaction = self._get_or_create_interaction(command.post_id, command.user_id)
        if interaction.ever_unshared:
            logger.info(
                "Ignoring duplicate post unshare interaction: post_id=%s, user_id=%s",
                command.post_id,
                command.user_id,
            )
            return

        self.post_interaction_updater.apply(
            post_id=command.post_id,
            user_id=command.user_id,
            metric_updates=[
                InteractionMetricUpdate(
                    metric=InteractionMetric.SHARES,
                    raw_delta=-1,
                    decayed_delta=-1,
                )
            ],
            embedding_weight=-(POST_SHARE_WEIGHT * POST_UNSHARE_PENALISATION_WEIGHT),
            occurred_at=command.occurred_at,
        )
        interaction.ever_unshared = True
        self.user_post_interactions_repository.save(interaction)

    def _get_or_create_interaction(self, post_id, user_id) -> UserPostInteractions:
        return (
            self.user_post_interactions_repository.get(post_id, user_id)
            or UserPostInteractions(post_id=post_id, user_id=user_id)
        )


DeletePostShareUsecase = DeletePostShareInteractionUsecase
