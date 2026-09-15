from datetime import timedelta
from uuid import uuid4

import pytest

from pipeline.model.post.post_interaction_features import PostInteractionFeatures
from shared.helpers import decay
from test._support.builders import T0


pytestmark = pytest.mark.unit


def test_initial_decayed_engagement_score_defaults_to_zero():
    # Arrange / Act
    features = PostInteractionFeatures(
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
    )

    # Assert
    assert features.decayed_engagement_score == 0.0
    assert features.last_updated_at == T0


def test_update_decayed_engagement_score_applies_decay_and_adds_weight():
    # Arrange
    features = PostInteractionFeatures(
        post_id=uuid4(),
        impressions=10,
        views=5,
        likes=2,
        comments=1,
        shares=0,
        fast_skips=0,
        collab_requests=0,
        collab_requests_accepted=0,
        watch_time_average_percent=0.0,
        watch_time=0.0,
        last_updated_at=T0,
        decayed_engagement_score=10.0,
    )
    occurred_at = T0 + timedelta(seconds=2)
    weight = 2.5
    expected_decay_factor = decay(T0, occurred_at)

    # Act
    features.update_decayed_engagement_score(occurred_at, weight)

    # Assert
    expected_score = (10.0 * expected_decay_factor) + weight
    assert features.decayed_engagement_score == pytest.approx(expected_score)
    assert features.last_updated_at == occurred_at
