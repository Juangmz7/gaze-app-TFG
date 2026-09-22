from datetime import timedelta
from uuid import uuid4

import pytest

from pipeline.model.user.user_features import UserFeatures
from shared.helpers import decay, l2_normalize_vector
from test._support.builders import T0


pytestmark = pytest.mark.unit


def test_initial_zero_accumulator_has_no_semantic_signal():
    # Arrange / Act
    features = UserFeatures(
        user_id=uuid4(),
        semantic_embedding=[0.0, 0.0, 0.0],
        last_updated_at=T0,
        has_semantic_signal=False,
    )

    # Assert
    assert features.semantic_embedding == [0.0, 0.0, 0.0]
    assert features.has_semantic_signal is False


def test_first_meaningful_semantic_interaction_sets_signal():
    # Arrange
    occurred_at = T0 + timedelta(seconds=1)
    features = UserFeatures(
        user_id=uuid4(),
        semantic_embedding=[0.0, 0.0, 0.0],
        last_updated_at=T0,
        has_semantic_signal=False,
    )

    # Act
    features.apply_semantic_interaction(
        post_semantic_embedding=[0.2, 0.4, 0.8],
        embedding_weight=2.0,
        source_weight=1.25,
        occurred_at=occurred_at,
    )

    # Assert
    assert features.has_semantic_signal is True


def test_first_meaningful_semantic_interaction_normalizes_vector():
    # Arrange
    occurred_at = T0 + timedelta(seconds=1)
    features = UserFeatures(
        user_id=uuid4(),
        semantic_embedding=[0.0, 0.0, 0.0],
        last_updated_at=T0,
        has_semantic_signal=False,
    )

    # Act
    features.apply_semantic_interaction(
        post_semantic_embedding=[0.2, 0.4, 0.8],
        embedding_weight=2.0,
        source_weight=1.25,
        occurred_at=occurred_at,
    )

    # Assert
    assert features.semantic_embedding == pytest.approx(l2_normalize_vector([0.5, 1.0, 2.0]))


def test_first_meaningful_semantic_interaction_updates_timestamp():
    # Arrange
    occurred_at = T0 + timedelta(seconds=1)
    features = UserFeatures(
        user_id=uuid4(),
        semantic_embedding=[0.0, 0.0, 0.0],
        last_updated_at=T0,
        has_semantic_signal=False,
    )

    # Act
    features.apply_semantic_interaction(
        post_semantic_embedding=[0.2, 0.4, 0.8],
        embedding_weight=2.0,
        source_weight=1.25,
        occurred_at=occurred_at,
    )

    # Assert
    assert features.last_updated_at == occurred_at


def test_old_embedding_is_decayed_before_adding_weighted_post_embedding():
    # Arrange
    occurred_at = T0 + timedelta(seconds=2)
    factor = decay(T0, occurred_at)
    features = UserFeatures(
        user_id=uuid4(),
        semantic_embedding=[0.2, 0.5, 0.8],
        last_updated_at=T0,
        has_semantic_signal=True,
    )

    # Act
    features.apply_semantic_interaction(
        post_semantic_embedding=[0.1, 0.3, 0.6],
        embedding_weight=2.0,
        source_weight=1.5,
        occurred_at=occurred_at,
    )

    # Assert
    expected_raw = [
        0.2 * factor + 0.1 * 2.0 * 1.5,
        0.5 * factor + 0.3 * 2.0 * 1.5,
        0.8 * factor + 0.6 * 2.0 * 1.5,
    ]
    assert features.semantic_embedding == pytest.approx(l2_normalize_vector(expected_raw))


def test_mismatched_embedding_dimensions_raise_value_error_without_mutating_state():
    # Arrange
    features = UserFeatures(
        user_id=uuid4(),
        semantic_embedding=[0.0, 0.0],
        last_updated_at=T0,
        has_semantic_signal=False,
    )

    # Act / Assert
    with pytest.raises(ValueError):
        features.apply_semantic_interaction(
            post_semantic_embedding=[0.1],
            embedding_weight=1.0,
            source_weight=1.0,
            occurred_at=T0,
        )

    assert features.semantic_embedding == [0.0, 0.0]
    assert features.has_semantic_signal is False
