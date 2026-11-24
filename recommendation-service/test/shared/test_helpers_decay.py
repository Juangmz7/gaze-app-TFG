from datetime import datetime, timedelta, timezone
import math

import pytest

import shared.helpers as helpers
from pipeline.config.constants import ENGAGEMENT_DECAY_RATE_PER_SECOND
from shared.helpers import decay


pytestmark = pytest.mark.unit
T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


def test_decay_uses_the_shared_engagement_decay_rate_constant():
    # Arrange
    elapsed_seconds = 2.5

    # Act
    factor = decay(T0, T0 + timedelta(seconds=elapsed_seconds))

    # Assert
    assert factor == pytest.approx(math.exp(-ENGAGEMENT_DECAY_RATE_PER_SECOND * elapsed_seconds))


def test_decay_follows_a_change_of_the_shared_constant(monkeypatch):
    # Arrange
    monkeypatch.setattr(helpers, "ENGAGEMENT_DECAY_RATE_PER_SECOND", 0.01)

    # Act
    factor = decay(T0, T0 + timedelta(seconds=10))

    # Assert
    assert factor == pytest.approx(math.exp(-0.1))


def test_decay_of_a_future_last_update_is_one():
    # Act
    factor = decay(T0 + timedelta(seconds=5), T0)

    # Assert
    assert factor == 1.0
