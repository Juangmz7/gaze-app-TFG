
from datetime import datetime
import math

from pipeline.config.constants import InteractionSourceMultiplier
from rabbitmq.event.post.post_events import InteractionSource


def decay(last_updated_at: datetime) -> float:
    """
    Calculate the decay factor based on the time since the last update.
    The decay factor decreases as the time since the last update increases.
    """
    decay_rate = 0.3
    time_since_last_update_seconds = (datetime.now() - last_updated_at).seconds

    return math.exp(-decay_rate * time_since_last_update_seconds)

def get_view_source_weight(self, source: InteractionSource):
        if source == InteractionSource.HOME_FEED:
            return InteractionSourceMultiplier.HOME_FEED.value
        elif source == InteractionSource.PROFILE:
            return InteractionSourceMultiplier.USER_PROFILE.value
        elif source == InteractionSource.SEARCH:
            return InteractionSourceMultiplier.SEARCH.value
        else:
            return 1.0