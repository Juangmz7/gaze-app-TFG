import pytest

from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats
from shared.enum.interaction_metric import InteractionMetric


pytestmark = pytest.mark.unit


@pytest.mark.parametrize(
    ("metric", "attribute", "delta"),
    [
        (InteractionMetric.IMPRESSIONS, "impressions", 1),
        (InteractionMetric.VIEWS, "views", 1),
        (InteractionMetric.VIEW_ENGAGEMENT, "views", 1),
        (InteractionMetric.LIKES, "likes", 1),
        (InteractionMetric.COMMENTS, "comments", -1),
        (InteractionMetric.COMMENT_LIKES, "comments_likes", -1),
        (InteractionMetric.SHARES, "shares", 2),
        (InteractionMetric.FAST_SKIPS, "fast_skips", 1),
        (InteractionMetric.COLLAB_REQUESTS, "collab_requests", 1),
        (InteractionMetric.COLLAB_REQUESTS_ACCEPTED, "collab_requests_accepted", 1),
        (InteractionMetric.WATCH_TIME_AVERAGE_PERCENT, "watch_time_average_percent", 0.75),
        (InteractionMetric.WATCH_TIME, "watch_time", 900.0),
    ],
)
def test_increment_updates_expected_raw_metric(metric, attribute, delta):
    # Arrange
    stats = RawInteractionStats.empty()

    # Act
    stats.increment(metric, delta)

    # Assert
    assert getattr(stats, attribute) == delta


def test_skips_alias_updates_fast_skips():
    # Arrange
    stats = RawInteractionStats.empty()

    # Act
    stats.skips = 3

    # Assert
    assert stats.fast_skips == 3
    assert stats.skips == 3
