import logging

from post.command.post_commands import DeletePostCommentLikeCommand


logger = logging.getLogger(__name__)


class DeletePostCommentLikeUsecase:
    def execute(command: DeletePostCommentLikeCommand) -> None:
        logger.info(
            "Processing post comment like deletion: event_id=%s, correlation_id=%s",
            command.event_id,
            command.correlation_id,
        )
        logger.debug(
            "Post comment like deletion has no recommendation projection update: event_id=%s, correlation_id=%s",
            command.event_id,
            command.correlation_id,
        )
        pass
