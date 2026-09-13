
from shared.enum.interaction_metric import InteractionMetric


class RawInteractionStats:
    def __init__(
            self,
            impressions: int,
            views: int,
            likes: int,
            comments: int,
            comments_likes: int,
            shares: int,
            fast_skips: int,
            collab_requests: int,
            collab_requests_accepted: int,
            watch_time_average_percent: float,
            watch_time: float,
    ):
        self.impressions = impressions
        self.views = views
        self.likes = likes
        self.comments = comments
        self.comments_likes = comments_likes
        self.shares = shares
        self.fast_skips = fast_skips
        self.collab_requests = collab_requests
        self.collab_requests_accepted = collab_requests_accepted
        self.watch_time_average_percent = watch_time_average_percent
        self.watch_time = watch_time

    @classmethod
    def empty(cls) -> "RawInteractionStats":
        return cls(
            impressions=0,
            views=0,
            likes=0,
            comments=0,
            comments_likes=0,
            shares=0,
            fast_skips=0,
            collab_requests=0,
            collab_requests_accepted=0,
            watch_time_average_percent=0.0,
            watch_time=0.0,
        )

    def increment(self, metric: InteractionMetric, delta: int | float) -> None:
        attribute = metric.raw_stats_attribute
        if not hasattr(self, attribute):
            raise ValueError(f"Metric {metric.value} is not supported by raw interaction stats")

        setattr(self, attribute, getattr(self, attribute) + delta)

    @property
    def skips(self) -> int:
        return self.fast_skips

    @skips.setter
    def skips(self, value: int) -> None:
        self.fast_skips = value
