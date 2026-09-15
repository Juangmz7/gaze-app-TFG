
from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats


class PostInteractionFeatures():
    def __init__(
            self,
            impressions: int,
            views: int,
            likes: int,
            comments: int,
            shares: int,
            fast_skips: int,
            collab_requests: int,
            collab_requests_accepted: int,
            watch_time_average_percent: float,
            watch_time: float,
            engagement_score: float
    ):
        self.impressions = impressions
        self.views = views
        self.likes = likes
        self.comments = comments
        self.shares = shares
        self.fast_skips = fast_skips
        self.collab_requests = collab_requests
        self.collab_requests_accepted = collab_requests_accepted
        self.watch_time_average_percent = watch_time_average_percent
        self.watch_time = watch_time
        self.engagement_score = self._recalculate_engagement_score()