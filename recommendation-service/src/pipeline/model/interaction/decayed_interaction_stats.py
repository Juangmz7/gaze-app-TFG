
from shared.enum.interaction_metric import InteractionMetric


class DecayedInteractionStats:
    _DECAYED_ATTRIBUTES = (
        "impressions",
        "views_engagement",
        "likes",
        "comments",
        "comments_likes",
        "shares",
        "fast_skips",
        "collab_requests",
        "collab_requests_accepted",
        "watch_time_average_percent",
        "watch_time",
    )

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
            watch_time_average_percent: float,
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
        self.watch_time_average_percent = watch_time_average_percent
        self.watch_time = watch_time

    def apply_decay(self, factor: float) -> None:
        for attribute in self._DECAYED_ATTRIBUTES:
            setattr(self, attribute, getattr(self, attribute) * factor)

    def increment(self, metric: InteractionMetric, delta: int | float) -> None:
        attribute = metric.decayed_stats_attribute
        if attribute not in self._DECAYED_ATTRIBUTES:
            raise ValueError(f"Metric {metric.value} is not supported by decayed interaction stats")

        setattr(self, attribute, getattr(self, attribute) + delta)

    @property
    def skips(self) -> float:
        return self.fast_skips

    @skips.setter
    def skips(self, value: float) -> None:
        self.fast_skips = value
