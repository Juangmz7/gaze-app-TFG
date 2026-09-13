
from datetime import datetime
import math

from pipeline.config.constants import InteractionSourceMultiplier
from rabbitmq.event.post.post_events import InteractionSource


def decay(last_updated_at: datetime, occurred_at: datetime | None = None) -> float:
    """
    Calculate the decay factor based on the time since the last update.
    The decay factor decreases as the time since the last update increases.
    """
    decay_rate = 0.3
    reference_time = occurred_at or datetime.now(tz=last_updated_at.tzinfo)
    if last_updated_at.tzinfo is None and reference_time.tzinfo is not None:
        reference_time = reference_time.replace(tzinfo=None)
    elif last_updated_at.tzinfo is not None and reference_time.tzinfo is None:
        reference_time = reference_time.replace(tzinfo=last_updated_at.tzinfo)

    time_since_last_update_seconds = max(
        0.0,
        (reference_time - last_updated_at).total_seconds(),
    )

    return math.exp(-decay_rate * time_since_last_update_seconds)


def normalize_vector_0_1(values: list[float]) -> list[float]:
    if not values:
        return []

    minimum = min(values)
    maximum = max(values)
    if math.isclose(minimum, maximum):
        return [0.0 for _ in values]

    scale = maximum - minimum
    return [(value - minimum) / scale for value in values]

def get_view_source_weight(source: InteractionSource) -> float:
    if source == InteractionSource.HOME_FEED:
        return InteractionSourceMultiplier.HOME_FEED.value
    elif source == InteractionSource.USER_PROFILE:
        return InteractionSourceMultiplier.USER_PROFILE.value
    elif source == InteractionSource.SEARCH:
        return InteractionSourceMultiplier.SEARCH.value
    else:
        return 1.0


def build_post_semantic_text(
        description: str | None,
        tags: set[str] | list[str],
        tagged_users: set[str] | list[str],
        collab_title: str | None = None,
) -> str:
    parts = []
    if collab_title:
        parts.append(collab_title)
    if description:
        parts.append(description)
    if tags:
        parts.append(" ".join(sorted(tags)))
    if tagged_users:
        parts.append(" ".join(sorted(tagged_users)))
    return " ".join(parts)
