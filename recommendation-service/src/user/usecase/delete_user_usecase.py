import logging
from pipeline.repository.user_features_repository import UserFeaturesRepository
from user.command.user_commands import DeleteUserCommand


logger = logging.getLogger(__name__)


class DeleteUserUsecase:
    def __init__(self, user_features_repository: UserFeaturesRepository):
        self.user_features_repository = user_features_repository

    def execute(self, command: DeleteUserCommand) -> None:
        self.user_features_repository.delete(command.user_id)
        logger.info(f"User {command.user_id} deleted successfully")
