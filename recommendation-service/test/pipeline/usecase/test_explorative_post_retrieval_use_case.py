from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.repository.explorative_post_retrieval_repository import ExplorativePostRetrievalRepository
from pipeline.usecase.explorative_post_retrieval_use_case import ExplorativePostRetrievalUsecase


pytestmark = pytest.mark.unit


def test_retrieve_posts_returns_correct_number_of_candidates():
    # Arrange
    user_id = uuid4()
    post_id_popular = uuid4()
    post_id_random = uuid4()
    post_id_unseen = uuid4()

    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_popular_posts.return_value = [(post_id_popular, 10.0)]
    repository.get_random_posts.return_value = [(post_id_random, 5.0)]
    repository.get_unseen_tags_posts.return_value = [(post_id_unseen, 7.5)]

    usecase = ExplorativePostRetrievalUsecase(repository)

    # Act
    candidates = usecase.retrieve_posts(user_id)

    # Assert
    assert len(candidates) == 3


def test_retrieve_posts_aggregates_popular_posts():
    # Arrange
    user_id = uuid4()
    post_id_popular = uuid4()
    post_id_random = uuid4()
    post_id_unseen = uuid4()

    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_popular_posts.return_value = [(post_id_popular, 10.0)]
    repository.get_random_posts.return_value = [(post_id_random, 5.0)]
    repository.get_unseen_tags_posts.return_value = [(post_id_unseen, 7.5)]

    usecase = ExplorativePostRetrievalUsecase(repository)

    # Act
    candidates = usecase.retrieve_posts(user_id)

    # Assert
    assert candidates[0].post_id == post_id_popular
    assert candidates[0].source == PostRetrieveSource.POPULAR
    assert candidates[0].score == 10.0


def test_retrieve_posts_aggregates_random_posts():
    # Arrange
    user_id = uuid4()
    post_id_popular = uuid4()
    post_id_random = uuid4()
    post_id_unseen = uuid4()

    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_popular_posts.return_value = [(post_id_popular, 10.0)]
    repository.get_random_posts.return_value = [(post_id_random, 5.0)]
    repository.get_unseen_tags_posts.return_value = [(post_id_unseen, 7.5)]

    usecase = ExplorativePostRetrievalUsecase(repository)

    # Act
    candidates = usecase.retrieve_posts(user_id)

    # Assert
    assert candidates[1].post_id == post_id_random
    assert candidates[1].source == PostRetrieveSource.RANDOM
    assert candidates[1].score == 5.0


def test_retrieve_posts_aggregates_unseen_tags_posts():
    # Arrange
    user_id = uuid4()
    post_id_popular = uuid4()
    post_id_random = uuid4()
    post_id_unseen = uuid4()

    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_popular_posts.return_value = [(post_id_popular, 10.0)]
    repository.get_random_posts.return_value = [(post_id_random, 5.0)]
    repository.get_unseen_tags_posts.return_value = [(post_id_unseen, 7.5)]

    usecase = ExplorativePostRetrievalUsecase(repository)

    # Act
    candidates = usecase.retrieve_posts(user_id)

    # Assert
    assert candidates[2].post_id == post_id_unseen
    assert candidates[2].source == PostRetrieveSource.UNSEEN_TAG
    assert candidates[2].score == 7.5


def test_retrieve_posts_for_new_user_returns_correct_number_of_candidates():
    # Arrange
    user_id = uuid4()
    post_id_popular = uuid4()
    post_id_random = uuid4()

    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_popular_posts.return_value = [(post_id_popular, 10.0)]
    repository.get_random_posts.return_value = [(post_id_random, 5.0)]
    repository.get_unseen_tags_posts.return_value = []

    usecase = ExplorativePostRetrievalUsecase(repository)

    # Act
    candidates = usecase.retrieve_posts_for_new_user(user_id)

    # Assert
    assert len(candidates) == 2


def test_retrieve_posts_for_new_user_aggregates_popular_posts():
    # Arrange
    user_id = uuid4()
    post_id_popular = uuid4()
    post_id_random = uuid4()

    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_popular_posts.return_value = [(post_id_popular, 10.0)]
    repository.get_random_posts.return_value = [(post_id_random, 5.0)]
    repository.get_unseen_tags_posts.return_value = []

    usecase = ExplorativePostRetrievalUsecase(repository)

    # Act
    candidates = usecase.retrieve_posts_for_new_user(user_id)

    # Assert
    assert candidates[0].post_id == post_id_popular
    assert candidates[0].source == PostRetrieveSource.POPULAR
    assert candidates[0].score == 10.0


def test_retrieve_posts_for_new_user_aggregates_random_posts():
    # Arrange
    user_id = uuid4()
    post_id_popular = uuid4()
    post_id_random = uuid4()

    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_popular_posts.return_value = [(post_id_popular, 10.0)]
    repository.get_random_posts.return_value = [(post_id_random, 5.0)]
    repository.get_unseen_tags_posts.return_value = []

    usecase = ExplorativePostRetrievalUsecase(repository)

    # Act
    candidates = usecase.retrieve_posts_for_new_user(user_id)

    # Assert
    assert candidates[1].post_id == post_id_random
    assert candidates[1].source == PostRetrieveSource.RANDOM
    assert candidates[1].score == 5.0


def test_retrieve_posts_for_new_user_does_not_call_unseen_tags_posts():
    # Arrange
    user_id = uuid4()
    post_id_popular = uuid4()
    post_id_random = uuid4()

    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_popular_posts.return_value = [(post_id_popular, 10.0)]
    repository.get_random_posts.return_value = [(post_id_random, 5.0)]
    repository.get_unseen_tags_posts.return_value = []

    usecase = ExplorativePostRetrievalUsecase(repository)

    # Act
    usecase.retrieve_posts_for_new_user(user_id)

    # Assert
    repository.get_unseen_tags_posts.assert_not_called()


def test_retrieve_posts_passes_effective_scores_through_unchanged():
    # Arrange
    user_id = uuid4()
    popular_ids = [uuid4(), uuid4()]
    random_id = uuid4()
    unseen_id = uuid4()

    repository = Mock(spec=ExplorativePostRetrievalRepository)
    repository.get_popular_posts.return_value = [(popular_ids[0], 4.07436e-5), (popular_ids[1], -0.3704)]
    repository.get_random_posts.return_value = [(random_id, 9.8e-305)]
    repository.get_unseen_tags_posts.return_value = [(unseen_id, 1.2345678901)]

    usecase = ExplorativePostRetrievalUsecase(repository)

    # Act
    candidates = usecase.retrieve_posts(user_id)

    # Assert
    assert [(c.post_id, c.score) for c in candidates] == [
        (popular_ids[0], 4.07436e-5),
        (popular_ids[1], -0.3704),
        (random_id, 9.8e-305),
        (unseen_id, 1.2345678901),
    ]
