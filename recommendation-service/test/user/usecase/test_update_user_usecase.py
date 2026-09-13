from datetime import datetime, timezone
from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.repository.user_features_repository import UserFeaturesRepository
from rabbitmq.event.user.user_events import UserBioEventPayload
from user.command.user_commands import UpdateUserCommand
from user.usecase.update_user_usecase import UpdateUserUsecase


pytestmark = pytest.mark.unit


def test_update_user_uses_description_and_social_media_as_semantic_text():
    # Arrange
    repository = Mock(spec=UserFeaturesRepository)
    embedding_service = Mock()
    embedding_service.encode.return_value = [0.3, 0.4]
    usecase = UpdateUserUsecase(repository, embedding_service)
    occurred_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    updated_at = datetime(2026, 1, 2, tzinfo=timezone.utc)
    command = UpdateUserCommand(
        event_id=uuid4(),
        correlation_id=uuid4(),
        occurred_at=occurred_at,
        user_id=uuid4(),
        username="user",
        email="user@example.test",
        bio=UserBioEventPayload(
            description="bio",
            socialMedia={"site": "portfolio", "github": "code"},
        ),
        picture_url=None,
        account_status=None,
        created_at=None,
        updated_at=updated_at,
    )

    # Act
    usecase.execute(command)

    # Assert
    embedding_service.encode.assert_called_once_with("bio code portfolio")
    saved = repository.save.call_args.args[0]
    assert saved.semantic_embedding == [0.3, 0.4]
    assert saved.last_updated_at == updated_at
    assert saved.has_semantic_signal is True
