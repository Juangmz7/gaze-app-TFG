from datetime import timedelta

import pytest

from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from shared.enum.interaction_metric import InteractionMetric
from shared.helpers import decay
from test._support.builders import T0, decayed_stats, make_user_creator_features, raw_stats


pytestmark = pytest.mark.unit


def test_apply_interaction_updates_updates_raw_decayed_affinity_and_timestamp():
    # Arrange
    occurred_at = T0 + timedelta(seconds=2)
    factor = decay(T0, occurred_at)
    features = make_user_creator_features(
        raw_interaction_stats=raw_stats(likes=2),
        decayed_interaction_stats=decayed_stats(impressions=10, likes=4),
        last_updated_at=T0,
    )

    # Act
    features.apply_interaction_updates(
        [InteractionMetricUpdate(InteractionMetric.LIKES, raw_delta=1, decayed_delta=1)],
        occurred_at,
    )

    # Assert
    assert features.raw_interaction_stats.likes == 3
    assert features.decayed_interaction_stats.impressions == pytest.approx(10 * factor)
    assert features.decayed_interaction_stats.likes == pytest.approx(4 * factor + 1)
    assert features.last_updated_at == occurred_at
    assert features.affinity_score == features.calculate_affinity_score()
