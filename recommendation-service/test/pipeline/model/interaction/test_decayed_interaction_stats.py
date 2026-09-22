from datetime import datetime, timedelta, timezone

import pytest

from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from shared.enum.interaction_metric import InteractionMetric
from shared.helpers import decay


pytestmark = pytest.mark.unit


def test_increment_decays_all_metrics():
    # Arrange
    last_updated_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    occurred_at = last_updated_at + timedelta(seconds=2)
    factor = decay(last_updated_at, occurred_at)
    stats = DecayedInteractionStats(
        impressions=10,
        views_engagement=8,
        likes=5,
        comments=4,
        comments_likes=3,
        shares=2,
        fast_skips=1,
        collab_requests=6,
        collab_requests_accepted=7,
        watch_time=9,
    )

    # Act
    stats.increment(
        [InteractionMetricUpdate(InteractionMetric.LIKES, decayed_delta=1)],
        last_updated_at,
        occurred_at,
    )

    # Assert
    assert stats.impressions == pytest.approx(10 * factor)
    assert stats.views_engagement == pytest.approx(8 * factor)
    assert stats.comments == pytest.approx(4 * factor)
    assert stats.comments_likes == pytest.approx(3 * factor)
    assert stats.shares == pytest.approx(2 * factor)
    assert stats.fast_skips == pytest.approx(1 * factor)
    assert stats.collab_requests == pytest.approx(6 * factor)
    assert stats.collab_requests_accepted == pytest.approx(7 * factor)
    assert stats.watch_time == pytest.approx(9 * factor)


def test_increment_increments_target_metric():
    # Arrange
    last_updated_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    occurred_at = last_updated_at + timedelta(seconds=2)
    factor = decay(last_updated_at, occurred_at)
    stats = DecayedInteractionStats(
        impressions=10,
        views_engagement=8,
        likes=5,
        comments=4,
        comments_likes=3,
        shares=2,
        fast_skips=1,
        collab_requests=6,
        collab_requests_accepted=7,
        watch_time=9,
    )

    # Act
    stats.increment(
        [InteractionMetricUpdate(InteractionMetric.LIKES, decayed_delta=1)],
        last_updated_at,
        occurred_at,
    )

    # Assert
    assert stats.likes == pytest.approx(5 * factor + 1)


@pytest.mark.parametrize(
    ("metric", "attribute", "delta"),
    [
        (InteractionMetric.VIEWS, "views_engagement", 0.8),
        (InteractionMetric.FAST_SKIPS, "fast_skips", 1),
        (InteractionMetric.LIKES, "likes", -0.25),
        (InteractionMetric.COMMENTS, "comments", -0.25),
        (InteractionMetric.SHARES, "shares", -0.25),
        (InteractionMetric.COLLAB_REQUESTS, "collab_requests", -0.25),
        (InteractionMetric.WATCH_TIME, "watch_time", 1200.0),
    ],
)
def test_increment_supports_various_metrics(metric, attribute, delta):
    # Arrange
    timestamp = datetime(2026, 1, 1, tzinfo=timezone.utc)
    stats = DecayedInteractionStats.empty()

    # Act
    stats.increment(
        [InteractionMetricUpdate(metric, decayed_delta=delta)],
        timestamp,
        timestamp,
    )

    # Assert
    assert getattr(stats, attribute) == pytest.approx(delta)


def test_zero_elapsed_time_applies_no_decay():
    # Arrange
    timestamp = datetime(2026, 1, 1, tzinfo=timezone.utc)
    stats = DecayedInteractionStats.empty()
    stats.likes = 5

    # Act
    stats.increment(
        [InteractionMetricUpdate(InteractionMetric.LIKES, decayed_delta=1)],
        timestamp,
        timestamp,
    )

    # Assert
    assert stats.likes == pytest.approx(6)


def test_long_elapsed_time_heavily_decays_before_new_delta():
    # Arrange
    last_updated_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    occurred_at = last_updated_at + timedelta(seconds=30)
    stats = DecayedInteractionStats.empty()
    stats.likes = 10

    # Act
    stats.increment(
        [InteractionMetricUpdate(InteractionMetric.LIKES, decayed_delta=1)],
        last_updated_at,
        occurred_at,
    )

    # Assert
    assert stats.likes == pytest.approx(10 * decay(last_updated_at, occurred_at) + 1)


def test_multiple_updates_share_one_decay_pass():
    # Arrange
    last_updated_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    occurred_at = last_updated_at + timedelta(seconds=1)
    factor = decay(last_updated_at, occurred_at)
    stats = DecayedInteractionStats.empty()
    stats.likes = 4
    stats.comments = 2

    # Act
    stats.increment(
        [
            InteractionMetricUpdate(InteractionMetric.LIKES, decayed_delta=1),
            InteractionMetricUpdate(InteractionMetric.COMMENTS, decayed_delta=1),
        ],
        last_updated_at,
        occurred_at,
    )

    # Assert
    assert stats.likes == pytest.approx(4 * factor + 1)
    assert stats.comments == pytest.approx(2 * factor + 1)
