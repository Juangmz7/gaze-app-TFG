from datetime import timedelta
from unittest.mock import Mock
from uuid import uuid4

import pytest

from pipeline.config.constants import InteractionSourceMultiplier, POST_LIKE_WEIGHT
from pipeline.exceptions.exceptions import PostFeaturesNotFoundException
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository
from pipeline.repository.user_features_repository import UserFeaturesRepository
from post.usecase.post_interaction_updater import PostInteractionUpdater
from rabbitmq.event.post.post_events import InteractionSource
from shared.enum.interaction_metric import InteractionMetric
from shared.helpers import decay, normalize_vector_0_1
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


pytestmark = pytest.mark.unit


from pipeline.repository.post_interaction_features_repository import PostInteractionFeaturesRepository

def _updater(
    *,
    creator_repository,
    tag_repository,
    user_repository,
    post_repository,
    post_interaction_repository=None,
    transaction_manager=None,
):
    if post_interaction_repository is None:
        post_interaction_repository = Mock(spec=PostInteractionFeaturesRepository)
        from pipeline.model.post.post_interaction_features import PostInteractionFeatures
        post_interaction_repository.get_for_update.return_value = PostInteractionFeatures(
            post_id=uuid4(),
            impressions=0,
            views=0,
            likes=0,
            comments=0,
            shares=0,
            fast_skips=0,
            collab_requests=0,
            collab_requests_accepted=0,
            watch_time_average_percent=0.0,
            watch_time=0.0,
            last_updated_at=T0,
            decayed_engagement_score=0.0
        )

    return PostInteractionUpdater(
        user_creator_features_repository=creator_repository,
        post_tag_features_repository=tag_repository,
        user_features_repository=user_repository,
        post_features_repository=post_repository,
        post_interaction_features_repository=post_interaction_repository,
        transaction_manager=transaction_manager,
    )


def test_apply_loads_features_updates_stats_recalculates_affinity_and_semantic_profile():
    # Arrange
    user_id = uuid4()
    creator_id = uuid4()
    post = make_post_features(
        creator_id=creator_id,
        tags=["ml", "python"],
        semantic_embedding=embedding(0.2, 0.4, 0.8),
    )
    creator_features = make_user_creator_features(
        user_id=user_id,
        creator_id=creator_id,
        raw_interaction_stats=raw_stats(likes=1),
        decayed_interaction_stats=decayed_stats(impressions=10, likes=2),
        last_updated_at=T0,
    )
    tag_features = [
        make_post_tag_features(
            user_id=user_id,
            tag_name="ml",
            raw_interaction_stats=raw_stats(likes=1),
            decayed_interaction_stats=decayed_stats(impressions=5, likes=1),
            last_updated_at=T0,
        ),
        make_post_tag_features(user_id=user_id, tag_name="python", last_updated_at=T0),
    ]
    user_features = make_user_features(
        user_id=user_id,
        semantic_embedding=embedding(0.1, 0.1, 0.1),
        last_updated_at=T0,
        has_semantic_signal=True,
    )
    post_repository = Mock(spec=PostFeaturesRepository)
    post_repository.get_post_features.return_value = post
    creator_repository = Mock(spec=UserCreatorFeaturesRepository)
    creator_repository.get_user_creator_features.return_value = creator_features
    creator_repository.get_user_creator_features_for_update.return_value = creator_features
    tag_repository = Mock(spec=PostTagFeaturesRepository)
    tag_repository.get_post_tag_features.return_value = tag_features
    tag_repository.get_post_tag_features_for_update.return_value = tag_features
    user_repository = Mock(spec=UserFeaturesRepository)
    user_repository.get_user_features_for_update.return_value = user_features
    updater = _updater(
        creator_repository=creator_repository,
        tag_repository=tag_repository,
        user_repository=user_repository,
        post_repository=post_repository,
    )

    # Act
    updater.apply(
        post_id=post.post_id,
        user_id=user_id,
        metric_updates=[
            InteractionMetricUpdate(InteractionMetric.LIKES, raw_delta=1, decayed_delta=1)
        ],
        embedding_weight=POST_LIKE_WEIGHT,
        occurred_at=T1,
        source=InteractionSource.SEARCH,
    )

    # Assert
    post_repository.get_post_features.assert_called_once_with(post.post_id)
    creator_repository.get_user_creator_features.assert_called_once_with(user_id, creator_id)
    creator_repository.get_user_creator_features_for_update.assert_called_once_with(user_id, creator_id)
    tag_repository.get_post_tag_features.assert_called_once_with(user_id, ["ml", "python"])
    tag_repository.get_post_tag_features_for_update.assert_called_once_with(user_id, ["ml", "python"])

    factor = decay(T0, T1)
    assert creator_features.raw_interaction_stats.likes == 2
    assert creator_features.decayed_interaction_stats.likes == pytest.approx(2 * factor + 1)
    assert creator_features.decayed_interaction_stats.impressions == pytest.approx(10 * factor)
    assert creator_features.last_updated_at == T1
    assert tag_features[0].raw_interaction_stats.likes == 2
    assert tag_features[0].decayed_interaction_stats.likes == pytest.approx(1 * factor + 1)
    assert creator_repository.save.call_args.args[0] is creator_features
    assert tag_repository.save_all.call_args.args[0] == tag_features

    expected_semantic_raw = [
        0.1 * factor + 0.2 * POST_LIKE_WEIGHT * InteractionSourceMultiplier.SEARCH.value,
        0.1 * factor + 0.4 * POST_LIKE_WEIGHT * InteractionSourceMultiplier.SEARCH.value,
        0.1 * factor + 0.8 * POST_LIKE_WEIGHT * InteractionSourceMultiplier.SEARCH.value,
    ] + [0.0] * 1021
    assert user_features.semantic_embedding == pytest.approx(normalize_vector_0_1(expected_semantic_raw))
    user_repository.save.assert_called_once_with(user_features)


def test_apply_creates_missing_creator_and_tag_rows_before_locking_them():
    # Arrange
    user_id = uuid4()
    post = make_post_features(tags=["a", "b"])
    creator_features = make_user_creator_features(user_id=user_id, creator_id=post.creator_id)
    tag_features = [
        make_post_tag_features(user_id=user_id, tag_name="a"),
        make_post_tag_features(user_id=user_id, tag_name="b"),
    ]
    post_repository = Mock(spec=PostFeaturesRepository)
    post_repository.get_post_features.return_value = post
    creator_repository = Mock(spec=UserCreatorFeaturesRepository)
    creator_repository.get_user_creator_features.return_value = None
    creator_repository.get_user_creator_features_for_update.return_value = creator_features
    tag_repository = Mock(spec=PostTagFeaturesRepository)
    tag_repository.get_post_tag_features.return_value = [tag_features[0]]
    tag_repository.get_post_tag_features_for_update.return_value = tag_features
    user_repository = Mock(spec=UserFeaturesRepository)
    user_repository.get_user_features_for_update.return_value = None
    updater = _updater(
        creator_repository=creator_repository,
        tag_repository=tag_repository,
        user_repository=user_repository,
        post_repository=post_repository,
    )

    # Act
    updater.apply(
        post_id=post.post_id,
        user_id=user_id,
        metric_updates=[
            InteractionMetricUpdate(InteractionMetric.SHARES, raw_delta=1, decayed_delta=1)
        ],
        embedding_weight=1.0,
        occurred_at=T1,
    )

    # Assert
    created_creator = creator_repository.create_if_absent.call_args.args[0]
    assert isinstance(created_creator, UserCreatorFeatures)
    assert created_creator.user_id == user_id
    assert created_creator.creator_id == post.creator_id
    created_tags = tag_repository.create_if_absent.call_args.args[0]
    assert len(created_tags) == 1
    assert isinstance(created_tags[0], PostTagFeatures)
    assert created_tags[0].tag_name == "b"
    user_repository.save.assert_not_called()


def test_apply_view_update_uses_watch_ratio_as_decayed_delta_without_double_weighting():
    # Arrange
    user_id = uuid4()
    post = make_post_features()
    creator_features = make_user_creator_features(user_id=user_id, creator_id=post.creator_id)
    post_repository = Mock(spec=PostFeaturesRepository)
    post_repository.get_post_features.return_value = post
    creator_repository = Mock(spec=UserCreatorFeaturesRepository)
    creator_repository.get_user_creator_features.return_value = creator_features
    creator_repository.get_user_creator_features_for_update.return_value = creator_features
    tag_repository = Mock(spec=PostTagFeaturesRepository)
    tag_repository.get_post_tag_features.return_value = []
    tag_repository.get_post_tag_features_for_update.return_value = []
    user_repository = Mock(spec=UserFeaturesRepository)
    user_repository.get_user_features_for_update.return_value = make_user_features(user_id=user_id)
    updater = _updater(
        creator_repository=creator_repository,
        tag_repository=tag_repository,
        user_repository=user_repository,
        post_repository=post_repository,
    )

    # Act
    updater.apply(
        post_id=post.post_id,
        user_id=user_id,
        metric_updates=[
            InteractionMetricUpdate(InteractionMetric.VIEWS, raw_delta=1, decayed_delta=0.75)
        ],
        embedding_weight=1.0,
        occurred_at=T1,
    )

    # Assert
    assert creator_features.raw_interaction_stats.views == 1
    assert creator_features.decayed_interaction_stats.views_engagement == pytest.approx(0.75)


def test_apply_fast_skip_negative_semantic_contribution_is_applied_once():
    # Arrange
    user_id = uuid4()
    occurred_at = T0 + timedelta(seconds=0)
    post = make_post_features(semantic_embedding=[0.0, 0.5, 1.0])
    creator_features = make_user_creator_features(user_id=user_id, creator_id=post.creator_id)
    user_features = make_user_features(
        user_id=user_id,
        semantic_embedding=[0.3, 0.3, 0.3],
        last_updated_at=T0,
        has_semantic_signal=True,
    )
    post_repository = Mock(spec=PostFeaturesRepository)
    post_repository.get_post_features.return_value = post
    creator_repository = Mock(spec=UserCreatorFeaturesRepository)
    creator_repository.get_user_creator_features.return_value = creator_features
    creator_repository.get_user_creator_features_for_update.return_value = creator_features
    tag_repository = Mock(spec=PostTagFeaturesRepository)
    tag_repository.get_post_tag_features.return_value = []
    tag_repository.get_post_tag_features_for_update.return_value = []
    user_repository = Mock(spec=UserFeaturesRepository)
    user_repository.get_user_features_for_update.return_value = user_features
    updater = _updater(
        creator_repository=creator_repository,
        tag_repository=tag_repository,
        user_repository=user_repository,
        post_repository=post_repository,
    )

    # Act
    updater.apply(
        post_id=post.post_id,
        user_id=user_id,
        metric_updates=[
            InteractionMetricUpdate(InteractionMetric.FAST_SKIPS, raw_delta=1, decayed_delta=1)
        ],
        embedding_weight=-1.0,
        occurred_at=occurred_at,
    )

    # Assert
    assert creator_features.raw_interaction_stats.fast_skips == 1
    assert creator_features.decayed_interaction_stats.fast_skips == pytest.approx(1)
    assert user_features.semantic_embedding == pytest.approx(normalize_vector_0_1([0.3, -0.2, -0.7]))


def test_apply_raises_when_post_features_are_missing():
    # Arrange
    post_repository = Mock(spec=PostFeaturesRepository)
    post_repository.get_post_features.return_value = None
    updater = _updater(
        creator_repository=Mock(spec=UserCreatorFeaturesRepository),
        tag_repository=Mock(spec=PostTagFeaturesRepository),
        user_repository=Mock(spec=UserFeaturesRepository),
        post_repository=post_repository,
    )

    # Act / Assert
    with pytest.raises(PostFeaturesNotFoundException):
        updater.apply(
            post_id=uuid4(),
            user_id=uuid4(),
            metric_updates=[
                InteractionMetricUpdate(InteractionMetric.LIKES, raw_delta=1, decayed_delta=1)
            ],
            embedding_weight=1.0,
            occurred_at=T1,
        )


def test_apply_rejects_empty_metric_updates():
    # Arrange
    updater = _updater(
        creator_repository=Mock(spec=UserCreatorFeaturesRepository),
        tag_repository=Mock(spec=PostTagFeaturesRepository),
        user_repository=Mock(spec=UserFeaturesRepository),
        post_repository=Mock(spec=PostFeaturesRepository),
    )

    # Act / Assert
    with pytest.raises(ValueError, match="At least one"):
        updater.apply(
            post_id=uuid4(),
            user_id=uuid4(),
            metric_updates=[],
            embedding_weight=1.0,
            occurred_at=T1,
        )
