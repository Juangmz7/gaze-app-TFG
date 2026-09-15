from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.repository.colaborative_post_retrieval_repository import CollaborativePostRetrievalRepository
from pipeline.usecase.collaborative_post_retrieval_use_case import CollaborativePostRetrievalUseCase


pytestmark = pytest.mark.unit


def test_retrieve_posts_returns_empty_when_no_similar_users():
    # Arrange
    user_id = uuid4()
    repository = Mock(spec=CollaborativePostRetrievalRepository)
    repository.get_similar_users.return_value = []
    usecase = CollaborativePostRetrievalUseCase(repository)

    # Act
    candidates = usecase.retrieve_posts(user_id)

    # Assert
    assert candidates == []
    repository.get_similar_users.assert_called_once()
    repository.get_posts_ordered_by_user_affinity.assert_not_called()


def test_retrieve_posts_returns_candidates():
    # Arrange
    user_id = uuid4()
    similar_users = [(uuid4(), 0.9), (uuid4(), 0.8)]
    post_id_1, post_id_2 = uuid4(), uuid4()
    posts = [(post_id_1, 0.75), (post_id_2, 0.60)]

    repository = Mock(spec=CollaborativePostRetrievalRepository)
    repository.get_similar_users.return_value = similar_users
    repository.get_posts_ordered_by_user_affinity.return_value = posts
    usecase = CollaborativePostRetrievalUseCase(repository)

    # Act
    candidates = usecase.retrieve_posts(user_id)

    # Assert
    assert len(candidates) == 2
    assert candidates[0].post_id == post_id_1
    assert candidates[0].source == PostRetrieveSource.COLLABORATIVE
    assert candidates[0].score == 0.75

    assert candidates[1].post_id == post_id_2
    assert candidates[1].source == PostRetrieveSource.COLLABORATIVE
    assert candidates[1].score == 0.60

    repository.get_similar_users.assert_called_once()
    repository.get_posts_ordered_by_user_affinity.assert_called_once_with(
        similar_users, user_id, 20, 20
    )
