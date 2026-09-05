import logging

from post.command.post_commands import CreatePostCollabCommand


logger = logging.getLogger(__name__)


class CreatePostCollabUsecase:
    def execute(command: CreatePostCollabCommand) -> None:
        logger.info(
            "Processing post collab creation: event_id=%s, correlation_id=%s, collab_id=%s, post_id=%s, user_id=%s",
            command.event_id,
            command.correlation_id,
            command.collab_id,
            command.post_id,
            command.user_id,
        )
        logger.debug(
            "Post collab creation has no recommendation projection update: event_id=%s, correlation_id=%s",
            command.event_id,
            command.correlation_id,
        )
