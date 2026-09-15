from datetime import datetime
from uuid import UUID

from pipeline.model.interaction.raw_interaction_stats import RawInteractionStats


class PostInteractionFeatures():
    def __init__(
            self,
            post_id: UUID,
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
            last_updated_at: datetime,
            decayed_engagement_score: float = 0.0,
    ):
        self.post_id = post_id
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
        self.last_updated_at = last_updated_at
        self.decayed_engagement_score = decayed_engagement_score

    def update_decayed_engagement_score(self, event_date: datetime, weight: float) -> None:
        from shared.helpers import decay
        decay_factor = decay(self.last_updated_at, event_date)
        self.decayed_engagement_score = self.decayed_engagement_score * decay_factor + weight
        self.last_updated_at = event_date