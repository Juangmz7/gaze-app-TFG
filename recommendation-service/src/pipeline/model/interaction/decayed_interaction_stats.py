
from ast import Set
from datetime import datetime

from pipeline.model.interaction.interaction_metric_update import InteractionMetricUpdate
from shared.enum.interaction_metric import InteractionMetric
from shared.helpers import decay


class DecayedInteractionStats:
    _DECAYED_ATTRIBUTES: frozenset[str] = frozenset({
        "impressions",
        "views_engagement",
        "likes",
        "comments",
        "comments_likes",
        "shares",
        "fast_skips",
        "collab_requests",
        "collab_requests_accepted",
        "watch_time",
    })

    def __init__(
            self,
            impressions: float,
            views_engagement: float,
            likes: float,
            comments: float,
            comments_likes: float,
            shares: float,
            fast_skips: float,
            collab_requests: float,
            collab_requests_accepted: float,
            watch_time: float,
    ):
        self.impressions = impressions
        self.views_engagement = views_engagement
        self.likes = likes
        self.comments = comments
        self.comments_likes = comments_likes
        self.shares = shares
        self.fast_skips = fast_skips
        self.collab_requests = collab_requests
        self.collab_requests_accepted = collab_requests_accepted
        self.watch_time = watch_time

    @classmethod
    def empty(cls) -> "DecayedInteractionStats":
        return cls(
            impressions=0.0,
            views_engagement=0.0,
            likes=0.0,
            comments=0.0,
            comments_likes=0.0,
            shares=0.0,
            fast_skips=0.0,
            collab_requests=0.0,
            collab_requests_accepted=0.0,
            watch_time=0.0,
        )

    def apply_decay(self, factor: float) -> None:
        for attribute in self._DECAYED_ATTRIBUTES:
            setattr(self, attribute, getattr(self, attribute) * factor)

    def increment(
            self,
            updates: list[InteractionMetricUpdate],
            last_updated_at: datetime,
            occurred_at: datetime
    ) -> None:
        decay_factor = decay(last_updated_at, occurred_at)
        self.apply_decay(decay_factor)

        for update in updates:
            attribute = update.metric.decayed_stats_attribute
            if attribute not in self._DECAYED_ATTRIBUTES:
                raise ValueError(f"Metric {update.metric.value} is not supported by decayed interaction stats")

            setattr(self, attribute, getattr(self, attribute) + update.decayed_delta)

    @property
    def skips(self) -> float:
        return self.fast_skips

    @skips.setter
    def skips(self, value: float) -> None:
        self.fast_skips = value
