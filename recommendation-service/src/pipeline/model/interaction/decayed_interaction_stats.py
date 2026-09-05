
class DecayedInteractionStats():
    def __init__(
            self,
            impressions: float,
            views_engagement: float,
            likes: float,
            comments: float,
            commentsLikes: float,
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
        self.commentsLikes = commentsLikes
        self.shares = shares
        self.skips = fast_skips
        self.collab_requests = collab_requests
        self.collab_requests_accepted = collab_requests_accepted
        self.watch_time_average_percent = watch_time_average_percent
        self.watch_time = watch_time