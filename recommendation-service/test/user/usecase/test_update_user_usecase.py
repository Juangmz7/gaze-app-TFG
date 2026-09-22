from datetime import datetime, timezone
from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.repository.user_features_repository import UserFeaturesRepository
from rabbitmq.event.user.user_events import UserBioEventPayload
from user.command.user_commands import UpdateUserCommand
from user.usecase.update_user_usecase import UpdateUserUsecase


pytestmark = pytest.mark.unit


def test_update_user_extracts_semantic_text_from_bio():
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


def test_update_user_updates_semantic_embedding():
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
    saved = repository.save.call_args.args[0]
    assert saved.semantic_embedding == [0.3, 0.4]


def test_update_user_updates_last_updated_at():
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
    saved = repository.save.call_args.args[0]
    assert saved.last_updated_at == updated_at


def test_update_user_updates_has_semantic_signal():
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
    saved = repository.save.call_args.args[0]
    assert saved.has_semantic_signal is True
