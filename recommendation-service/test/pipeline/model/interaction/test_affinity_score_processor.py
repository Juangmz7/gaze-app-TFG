import pytest

from pipeline.config.constants import (
    COLLAB_REQUEST_ACCEPT_WEIGHT,
    COLLAB_REQUEST_WEIGHT,
    FAST_SKIP_WEIGHT,
    POST_COMMENT_LIKE_WEIGHT,
    POST_COMMENT_WEIGHT,
    POST_LIKE_WEIGHT,
    POST_SHARE_WEIGHT,
    POST_VIEW_WEIGHT,
)
from pipeline.model.interaction.affinity_score_processor import AffinityScoreProcessor
from test._support.builders import decayed_stats


pytestmark = pytest.mark.unit


def test_calculate_affinity_score_uses_all_configured_weighted_signals():
    # Arrange
    stats = decayed_stats(
        impressions=10,
        views_engagement=8,
        likes=4,
        comments=3,
        comments_likes=2,
        shares=1,
        fast_skips=2,
        collab_requests=5,
        collab_requests_accepted=6,
    )

    # Act
    result = AffinityScoreProcessor(stats).calculate_affinity_score()

    # Assert
    expected = (
        (8 / 10) * POST_VIEW_WEIGHT
        + (4 / 10) * POST_LIKE_WEIGHT
        + (3 / 10) * POST_COMMENT_WEIGHT
        + (2 / 10) * POST_COMMENT_LIKE_WEIGHT
        + (1 / 10) * POST_SHARE_WEIGHT
        + (2 / 10) * FAST_SKIP_WEIGHT
        + 5 * COLLAB_REQUEST_WEIGHT
        + 6 * COLLAB_REQUEST_ACCEPT_WEIGHT
    )
    assert result == pytest.approx(expected)


def test_zero_impressions_skips_content_ratios_but_keeps_social_affinity():
    # Arrange
    stats = decayed_stats(
        impressions=0,
        likes=100,
        fast_skips=100,
        collab_requests=2,
        collab_requests_accepted=1,
    )

    # Act
    result = AffinityScoreProcessor(stats).calculate_affinity_score()

    # Assert
    assert result == pytest.approx(2 * COLLAB_REQUEST_WEIGHT + COLLAB_REQUEST_ACCEPT_WEIGHT)


def test_negative_fast_skip_weight_reduces_low_sample_content_affinity():
    # Arrange
    stats = decayed_stats(impressions=1, views_engagement=1, fast_skips=1)

    # Act
    result = AffinityScoreProcessor(stats).calculate_affinity_score()

    # Assert
    assert result == pytest.approx(POST_VIEW_WEIGHT + FAST_SKIP_WEIGHT)
