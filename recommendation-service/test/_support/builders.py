from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID, uuid4

from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats
from pipeline.model.post.post_features import PostFeatures
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.model.user.user_features import UserFeatures


T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)
T1 = datetime(2026, 1, 1, 0, 0, 2, tzinfo=timezone.utc)


def embedding(*values: float, size: int = 1024) -> list[float]:
    result = [0.0] * size
    for index, value in enumerate(values):
        result[index] = value
    return result


def raw_stats(**overrides) -> RawInteractionStats:
    values = {
        "impressions": 0,
        "views": 0,
        "likes": 0,
        "comments": 0,
        "comments_likes": 0,
        "shares": 0,
        "fast_skips": 0,
        "collab_requests": 0,
        "collab_requests_accepted": 0,
        "watch_time_average_percent": 0.0,
        "watch_time": 0.0,
    }
    values.update(overrides)
    return RawInteractionStats(**values)


def decayed_stats(**overrides) -> DecayedInteractionStats:
    values = {
        "impressions": 0.0,
        "views_engagement": 0.0,
        "likes": 0.0,
        "comments": 0.0,
        "comments_likes": 0.0,
        "shares": 0.0,
        "fast_skips": 0.0,
        "collab_requests": 0.0,
        "collab_requests_accepted": 0.0,
        "watch_time": 0.0,
    }
    values.update(overrides)
    return DecayedInteractionStats(**values)


def make_post_features(
    *,
    post_id: UUID | None = None,
    creator_id: UUID | None = None,
    tags: list[str] | None = None,
    semantic_embedding: list[float] | None = None,
    created_at: datetime = T0,
) -> PostFeatures:
    return PostFeatures(
        post_id=post_id or uuid4(),
        creator_id=creator_id or uuid4(),
        collab_id=None,
        collab_title=None,
        description="post",
        tags=tags if tags is not None else ["python", "ml"],
        tagged_users_ids=[],
        semantic_embedding=semantic_embedding or embedding(0.2, 0.4, 0.6),
        created_at=created_at,
    )


def make_user_creator_features(
    *,
    user_id: UUID | None = None,
    creator_id: UUID | None = None,
    raw_interaction_stats: RawInteractionStats | None = None,
    decayed_interaction_stats: DecayedInteractionStats | None = None,
    last_updated_at: datetime = T0,
) -> UserCreatorFeatures:
    return UserCreatorFeatures(
        user_id=user_id or uuid4(),
        creator_id=creator_id or uuid4(),
        raw_interaction_stats=raw_interaction_stats or raw_stats(),
        decayed_interaction_stats=decayed_interaction_stats or decayed_stats(),
        last_updated_at=last_updated_at,
    )


def make_post_tag_features(
    *,
    user_id: UUID | None = None,
    tag_name: str = "python",
    raw_interaction_stats: RawInteractionStats | None = None,
    decayed_interaction_stats: DecayedInteractionStats | None = None,
    last_updated_at: datetime = T0,
) -> PostTagFeatures:
    return PostTagFeatures(
        user_id=user_id or uuid4(),
        tag_name=tag_name,
        raw_interaction_stats=raw_interaction_stats or raw_stats(),
        decayed_interaction_stats=decayed_interaction_stats or decayed_stats(),
        last_updated_at=last_updated_at,
    )


def make_user_features(
    *,
    user_id: UUID | None = None,
    semantic_embedding: list[float] | None = None,
    last_updated_at: datetime = T0,
    has_semantic_signal: bool = False,
) -> UserFeatures:
    return UserFeatures(
        user_id=user_id or uuid4(),
        semantic_embedding=semantic_embedding or embedding(),
        last_updated_at=last_updated_at,
        has_semantic_signal=has_semantic_signal,
    )
