import logging

from post.command.post_commands import DeletePostCollabCommand


logger = logging.getLogger(__name__)


class DeletePostCollabUsecase:
    def execute(command: DeletePostCollabCommand) -> None:
        logger.info(
            "Processing post collab deletion: event_id=%s, correlation_id=%s, collab_id=%s, actioned_by=%s",
            command.event_id,
            command.correlation_id,
            command.collab_id,
            command.actioned_by,
        )
        logger.debug(
            "Post collab deletion has no recommendation projection update: event_id=%s, correlation_id=%s",
            command.event_id,
            command.correlation_id,
        )
