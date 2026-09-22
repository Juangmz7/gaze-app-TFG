from datetime import timedelta
from uuid import uuid4

import pytest

from pipeline.model.post.post_interaction_features import PostInteractionFeatures
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from shared.enum.interaction_metric import InteractionMetric
from shared.helpers import decay
from test._support.builders import T0, raw_stats, decayed_stats


pytestmark = pytest.mark.unit


def test_initial_decayed_engagement_score_defaults_to_zero():
    # Arrange / Act
    features = PostInteractionFeatures(
        post_id=uuid4(),
        raw_interaction_stats=raw_stats(impressions=0, views=0, likes=0, comments=0, shares=0, fast_skips=0, collab_requests=0, collab_requests_accepted=0, watch_time_average_percent=0.0, watch_time=0.0),
        decayed_interaction_stats=decayed_stats(impressions=0.0, views_engagement=0.0, likes=0.0, comments=0.0, shares=0.0, fast_skips=0.0, collab_requests=0.0, collab_requests_accepted=0.0, watch_time=0.0),
        last_updated_at=T0,
    )

    # Assert
    assert features.decayed_engagement_score == 0.0
    assert features.last_updated_at == T0


def test_apply_interaction_updates_updates_decayed_engagement_score():
    # Arrange
    features = PostInteractionFeatures(
        post_id=uuid4(),
        raw_interaction_stats=raw_stats(impressions=10, views=5, likes=2, comments=1, shares=0, fast_skips=0, collab_requests=0, collab_requests_accepted=0, watch_time_average_percent=0.0, watch_time=0.0),
        decayed_interaction_stats=decayed_stats(impressions=10.0, views_engagement=5.0, likes=2.0, comments=1.0, shares=0.0, fast_skips=0.0, collab_requests=0.0, collab_requests_accepted=0.0, watch_time=0.0),
        last_updated_at=T0,
        decayed_engagement_score=10.0,
    )
    occurred_at = T0 + timedelta(seconds=2)
    weight = 2.5
    expected_decay_factor = decay(T0, occurred_at)

    updates = [
        InteractionMetricUpdate(InteractionMetric.LIKES, raw_delta=1, decayed_delta=1),
    ]

    # Act
    features.apply_interaction_updates(updates, occurred_at, weight)

    # Assert
    expected_score = (10.0 * expected_decay_factor) + weight
    assert features.decayed_engagement_score == pytest.approx(expected_score)


def test_apply_interaction_updates_updates_last_updated_at():
    # Arrange
    features = PostInteractionFeatures(
        post_id=uuid4(),
        raw_interaction_stats=raw_stats(impressions=10, views=5, likes=2, comments=1, shares=0, fast_skips=0, collab_requests=0, collab_requests_accepted=0, watch_time_average_percent=0.0, watch_time=0.0),
        decayed_interaction_stats=decayed_stats(impressions=10.0, views_engagement=5.0, likes=2.0, comments=1.0, shares=0.0, fast_skips=0.0, collab_requests=0.0, collab_requests_accepted=0.0, watch_time=0.0),
        last_updated_at=T0,
        decayed_engagement_score=10.0,
    )
    occurred_at = T0 + timedelta(seconds=2)
    weight = 2.5

    updates = [
        InteractionMetricUpdate(InteractionMetric.LIKES, raw_delta=1, decayed_delta=1),
    ]

    # Act
    features.apply_interaction_updates(updates, occurred_at, weight)

    # Assert
    assert features.last_updated_at == occurred_at


def test_apply_interaction_updates_updates_raw_stats():
    # Arrange
    features = PostInteractionFeatures(
        post_id=uuid4(),
        raw_interaction_stats=raw_stats(impressions=10, views=5, likes=2, comments=1, shares=0, fast_skips=0, collab_requests=0, collab_requests_accepted=0, watch_time_average_percent=0.0, watch_time=0.0),
        decayed_interaction_stats=decayed_stats(impressions=10.0, views_engagement=5.0, likes=2.0, comments=1.0, shares=0.0, fast_skips=0.0, collab_requests=0.0, collab_requests_accepted=0.0, watch_time=0.0),
        last_updated_at=T0,
        decayed_engagement_score=10.0,
    )
    occurred_at = T0 + timedelta(seconds=2)
    weight = 2.5

    updates = [
        InteractionMetricUpdate(InteractionMetric.LIKES, raw_delta=1, decayed_delta=1),
    ]

    # Act
    features.apply_interaction_updates(updates, occurred_at, weight)

    # Assert
    assert features.raw_interaction_stats.likes == 3


def test_apply_interaction_updates_updates_decayed_stats():
    # Arrange
    features = PostInteractionFeatures(
        post_id=uuid4(),
        raw_interaction_stats=raw_stats(impressions=10, views=5, likes=2, comments=1, shares=0, fast_skips=0, collab_requests=0, collab_requests_accepted=0, watch_time_average_percent=0.0, watch_time=0.0),
        decayed_interaction_stats=decayed_stats(impressions=10.0, views_engagement=5.0, likes=2.0, comments=1.0, shares=0.0, fast_skips=0.0, collab_requests=0.0, collab_requests_accepted=0.0, watch_time=0.0),
        last_updated_at=T0,
        decayed_engagement_score=10.0,
    )
    occurred_at = T0 + timedelta(seconds=2)
    weight = 2.5
    expected_decay_factor = decay(T0, occurred_at)

    updates = [
        InteractionMetricUpdate(InteractionMetric.LIKES, raw_delta=1, decayed_delta=1),
    ]

    # Act
    features.apply_interaction_updates(updates, occurred_at, weight)

    # Assert
    assert features.decayed_interaction_stats.likes == pytest.approx((2.0 * expected_decay_factor) + 1.0)
