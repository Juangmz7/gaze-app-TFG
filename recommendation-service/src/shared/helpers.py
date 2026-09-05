
from datetime import datetime
import math


def decay(last_updated_at: datetime) -> float:
    """
    Calculate the decay factor based on the time since the last update.
    The decay factor decreases as the time since the last update increases.
    """
    decay_rate = 0.3
    time_since_last_update_seconds = (datetime.now() - last_updated_at).seconds

    return math.exp(-decay_rate * time_since_last_update_seconds)