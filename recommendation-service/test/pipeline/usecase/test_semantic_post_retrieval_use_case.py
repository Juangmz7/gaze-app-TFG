from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.repository.semantic_post_retrieval_repository import SemanticPostRetrievalRepository
from pipeline.usecase.semantic_post_retrieval_use_case import SemanticPostRetrievalUseCase


pytestmark = pytest.mark.unit


def test_retrieve_posts_returns_semantic_candidates():
    # Arrange
    user_id = uuid4()
    post_id_1, post_id_2 = uuid4(), uuid4()

    repository = Mock(spec=SemanticPostRetrievalRepository)
    repository.get_similar_posts.return_value = [(post_id_1, 0.95), (post_id_2, 0.85)]
    usecase = SemanticPostRetrievalUseCase(repository)

    # Act
    candidates = usecase.retrieve_posts(user_id)

    # Assert
    assert len(candidates) == 2

    assert candidates[0].post_id == post_id_1
    assert candidates[0].source == PostRetrieveSource.SEMANTIC
    assert candidates[0].score == 0.95

    assert candidates[1].post_id == post_id_2
    assert candidates[1].source == PostRetrieveSource.SEMANTIC
    assert candidates[1].score == 0.85

    repository.get_similar_posts.assert_called_once_with(user_id, 200)
