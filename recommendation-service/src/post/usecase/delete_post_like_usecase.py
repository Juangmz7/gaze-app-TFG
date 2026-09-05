import logging

from post.command.post_commands import DeletePostLikeCommand


logger = logging.getLogger(__name__)


class DeletePostLikeUsecase:
    def execute(command: DeletePostLikeCommand) -> None:
        logger.info(
            "Processing post like deletion: event_id=%s, correlation_id=%s, post_id=%s, user_id=%s",
            command.event_id,
            command.correlation_id,
            command.post_id,
            command.user_id,
        )
        logger.debug(
            "Post like deletion has no recommendation projection update: event_id=%s, correlation_id=%s",
            command.event_id,
            command.correlation_id,
        )
        pass
