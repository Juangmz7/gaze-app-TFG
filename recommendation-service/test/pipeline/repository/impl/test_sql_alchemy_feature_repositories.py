from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from uuid import uuid4
from pipeline.repository.impl.sql_alchemy_post_interaction_features_repository import SqlAlchemyPostInteractionFeaturesRepository
from shared.config.database import SQLAlchemySessionProvider


import pytest

from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from pipeline.repository.impl.sql_alchemy_post_features_repository import (
    SqlAlchemyPostFeaturesRepository,
)
from pipeline.repository.impl.sql_alchemy_post_tag_features_repository import (
    SqlAlchemyPostTagFeaturesRepository,
)
from pipeline.repository.impl.sql_alchemy_user_creator_features_repository import (
    SqlAlchemyUserCreatorFeaturesRepository,
)
from pipeline.repository.impl.sql_alchemy_user_features_repository import (
    SqlAlchemyUserFeaturesRepository,
)
from post.usecase.post_interaction_updater import PostInteractionUpdater
from shared.config.database import SQLAlchemyTransactionManager
from shared.enum.interaction_metric import InteractionMetric
from test._support.builders import (
    T0,
    T1,
    decayed_stats,
    embedding,
    make_post_features,
    make_post_tag_features,
    make_user_creator_features,
    make_user_features,
    raw_stats,
)


pytestmark = pytest.mark.integration


def test_user_creator_features_repository_upserts_and_loads_decayed_stats(session_provider):
    # Arrange
    repository = SqlAlchemyUserCreatorFeaturesRepository(session_provider)
    user_id = uuid4()
    creator_id = uuid4()
    first = make_user_creator_features(
        user_id=user_id,
        creator_id=creator_id,
        raw_interaction_stats=raw_stats(likes=1),
        decayed_interaction_stats=decayed_stats(impressions=10, likes=1.5),
    )
    updated = make_user_creator_features(
        user_id=user_id,
        creator_id=creator_id,
        raw_interaction_stats=raw_stats(likes=3),
        decayed_interaction_stats=decayed_stats(impressions=12, likes=2.5),
        last_updated_at=T1,
    )

    # Act
    repository.save(first)
    repository.save(updated)
    loaded = repository.get_user_creator_features(user_id, creator_id)

    # Assert
    assert loaded is not None
    assert loaded.raw_interaction_stats.likes == 3
    assert loaded.decayed_interaction_stats.impressions == pytest.approx(12)
    assert loaded.decayed_interaction_stats.likes == pytest.approx(2.5)
    assert loaded.last_updated_at == T1


def test_create_if_absent_preserves_existing_user_creator_features(session_provider):
    # Arrange
    repository = SqlAlchemyUserCreatorFeaturesRepository(session_provider)
    user_id = uuid4()
    creator_id = uuid4()
    existing = make_user_creator_features(
        user_id=user_id,
        creator_id=creator_id,
        raw_interaction_stats=raw_stats(likes=5),
    )
    replacement = make_user_creator_features(
        user_id=user_id,
        creator_id=creator_id,
        raw_interaction_stats=raw_stats(likes=0),
    )

    # Act
    repository.save(existing)
    repository.create_if_absent(replacement)
    loaded = repository.get_user_creator_features(user_id, creator_id)

    # Assert
    assert loaded.raw_interaction_stats.likes == 5


def test_post_tag_repository_returns_locked_rows_in_deterministic_tag_order(session_provider):
    # Arrange
    repository = SqlAlchemyPostTagFeaturesRepository(session_provider)
    user_id = uuid4()
    repository.save_all(
        [
            make_post_tag_features(user_id=user_id, tag_name="z"),
            make_post_tag_features(user_id=user_id, tag_name="a"),
            make_post_tag_features(user_id=user_id, tag_name="m"),
        ]
    )

    # Act
    loaded = repository.get_post_tag_features_for_update(user_id, ["z", "a", "m"])

    # Assert
    assert [feature.tag_name for feature in loaded] == ["a", "m", "z"]


def test_post_and_user_features_repositories_persist_pgvector_embeddings(session_provider):
    # Arrange
    post_repository = SqlAlchemyPostFeaturesRepository(session_provider)
    user_repository = SqlAlchemyUserFeaturesRepository(session_provider)

    post_interaction_repository = SqlAlchemyPostInteractionFeaturesRepository(session_provider)
    post = make_post_features(semantic_embedding=embedding(0.1, 0.2, 0.3))
    user = make_user_features(
        semantic_embedding=embedding(0.4, 0.5, 0.6),
        has_semantic_signal=True,
    )

    # Act
    post_repository.save(post)
    post_interaction_repository.create_empty(post.post_id, T0)
    user_repository.save(user)

    # Assert
    assert post_repository.get_post_features(post.post_id).semantic_embedding[:3] == pytest.approx([0.1, 0.2, 0.3])
    loaded_user = user_repository.get_user_features(user.user_id)
    assert loaded_user.semantic_embedding[:3] == pytest.approx([0.4, 0.5, 0.6])
    assert loaded_user.has_semantic_signal is True


def test_interaction_update_rolls_back_when_semantic_profile_save_fails(db_session_factory):
    # Arrange


    session_provider = SQLAlchemySessionProvider(db_session_factory)
    transaction_manager = SQLAlchemyTransactionManager(db_session_factory)
    post_repository = SqlAlchemyPostFeaturesRepository(session_provider)
    creator_repository = SqlAlchemyUserCreatorFeaturesRepository(session_provider)
    tag_repository = SqlAlchemyPostTagFeaturesRepository(session_provider)
    setup_user_repository = SqlAlchemyUserFeaturesRepository(session_provider)

    post_interaction_repository = SqlAlchemyPostInteractionFeaturesRepository(session_provider)

    class FailingUserFeaturesRepository(SqlAlchemyUserFeaturesRepository):
        def save(self, user_features):
            raise RuntimeError("semantic write failed")

    user_repository = FailingUserFeaturesRepository(session_provider)
    updater = PostInteractionUpdater(
        creator_repository,
        tag_repository,
        user_repository,
        post_repository,
        post_interaction_repository,
        transaction_manager,
    )
    user_id = uuid4()
    post = make_post_features(tags=["python"])
    post_repository.save(post)
    post_interaction_repository.create_empty(post.post_id, T0)
    setup_user_repository.save(make_user_features(user_id=user_id))

    # Act / Assert
    with pytest.raises(RuntimeError, match="semantic write failed"):
        updater.apply(
            post_id=post.post_id,
            user_id=user_id,
            metric_updates=[
                InteractionMetricUpdate(InteractionMetric.LIKES, raw_delta=1, decayed_delta=1)
            ],
            embedding_weight=1.0,
            occurred_at=T1,
        )

    assert creator_repository.get_user_creator_features(user_id, post.creator_id) is None
    assert tag_repository.get_post_tag_features(user_id, ["python"]) == []


def test_successful_interaction_update_commits_all_feature_changes(db_session_factory):
    # Arrange


    session_provider = SQLAlchemySessionProvider(db_session_factory)
    transaction_manager = SQLAlchemyTransactionManager(db_session_factory)
    post_repository = SqlAlchemyPostFeaturesRepository(session_provider)
    creator_repository = SqlAlchemyUserCreatorFeaturesRepository(session_provider)
    tag_repository = SqlAlchemyPostTagFeaturesRepository(session_provider)
    user_repository = SqlAlchemyUserFeaturesRepository(session_provider)

    post_interaction_repository = SqlAlchemyPostInteractionFeaturesRepository(session_provider)
    updater = PostInteractionUpdater(
        creator_repository,
        tag_repository,
        user_repository,
        post_repository,
        post_interaction_repository,
        transaction_manager,
    )
    user_id = uuid4()
    post = make_post_features(tags=["python"])
    post_repository.save(post)
    post_interaction_repository.create_empty(post.post_id, T0)
    user_repository.save(make_user_features(user_id=user_id))

    # Act
    updater.apply(
        post_id=post.post_id,
        user_id=user_id,
        metric_updates=[
            InteractionMetricUpdate(InteractionMetric.LIKES, raw_delta=1, decayed_delta=1)
        ],
        embedding_weight=1.0,
        occurred_at=T1,
    )

    # Assert
    creator = creator_repository.get_user_creator_features(user_id, post.creator_id)
    tags = tag_repository.get_post_tag_features(user_id, ["python"])
    user = user_repository.get_user_features(user_id)
    assert creator.raw_interaction_stats.likes == 1
    assert tags[0].raw_interaction_stats.likes == 1
    assert user.has_semantic_signal is True


def test_concurrent_like_updates_do_not_lose_increments(db_session_factory):
    # Arrange


    user_id = uuid4()
    creator_id = uuid4()
    base_provider = SQLAlchemySessionProvider(db_session_factory)
    base_repository = SqlAlchemyUserCreatorFeaturesRepository(base_provider)
    base_repository.save(
        make_user_creator_features(
            user_id=user_id,
            creator_id=creator_id,
            raw_interaction_stats=raw_stats(likes=0),
            decayed_interaction_stats=decayed_stats(likes=0),
            last_updated_at=T0,
        )
    )

    def apply_like(offset: int) -> None:
        provider = SQLAlchemySessionProvider(db_session_factory)
        repository = SqlAlchemyUserCreatorFeaturesRepository(provider)
        transaction_manager = SQLAlchemyTransactionManager(db_session_factory)
        with transaction_manager.transaction():
            features = repository.get_user_creator_features_for_update(user_id, creator_id)
            features.apply_interaction_updates(
                [
                    InteractionMetricUpdate(
                        InteractionMetric.LIKES,
                        raw_delta=1,
                        decayed_delta=1,
                    )
                ],
                T1 + timedelta(seconds=offset),
            )
            repository.save(features)

    # Act
    with ThreadPoolExecutor(max_workers=2) as executor:
        list(executor.map(apply_like, [0, 1]))

    # Assert
    loaded = base_repository.get_user_creator_features(user_id, creator_id)
    assert loaded.raw_interaction_stats.likes == 2
