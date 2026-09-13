from datetime import datetime, timezone
from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.repository.user_features_repository import UserFeaturesRepository
from rabbitmq.event.user.user_events import UserBioEventPayload
from user.command.user_commands import RegisterUserCommand
from user.usecase.register_user_usecase import RegisterUserUsecase


pytestmark = pytest.mark.unit


def _command(*, bio):
    return RegisterUserCommand(
        event_id=uuid4(),
        correlation_id=uuid4(),
        occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
        user_id=uuid4(),
        username="user",
        email="user@example.test",
        bio=bio,
    )


def test_register_user_with_onboarding_text_saves_encoded_semantic_profile():
    # Arrange
    repository = Mock(spec=UserFeaturesRepository)
    embedding_service = Mock()
    embedding_service.encode.return_value = [0.1, 0.2]
    usecase = RegisterUserUsecase(repository, embedding_service)
    command = _command(bio=UserBioEventPayload(description="I build recommender systems"))

    # Act
    usecase.execute(command)

    # Assert
    embedding_service.encode.assert_called_once_with("I build recommender systems")
    saved = repository.save.call_args.args[0]
    assert saved.user_id == command.user_id
    assert saved.semantic_embedding == [0.1, 0.2]
    assert saved.has_semantic_signal is True


@pytest.mark.parametrize("bio", [None, UserBioEventPayload(description=None)])
def test_register_user_without_onboarding_text_saves_zero_accumulator_without_signal(bio):
    # Arrange
    repository = Mock(spec=UserFeaturesRepository)
    embedding_service = Mock()
    embedding_service.zero_embedding.return_value = [0.0, 0.0]
    usecase = RegisterUserUsecase(repository, embedding_service)
    command = _command(bio=bio)

    # Act
    usecase.execute(command)

    # Assert
    embedding_service.encode.assert_not_called()
    saved = repository.save.call_args.args[0]
    assert saved.semantic_embedding == [0.0, 0.0]
    assert saved.has_semantic_signal is False
