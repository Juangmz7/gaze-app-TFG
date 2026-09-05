
class RawInteractionStats():
    def __init__(
            self,
            impressions: int,
            views: int,
            likes: int,
            comments: int,
            commentsLikes: int,
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
        self.commentsLikes = commentsLikes
        self.shares = shares
        self.skips = fast_skips
        self.collab_requests = collab_requests
        self.collab_requests_accepted = collab_requests_accepted
        self.watch_time_average_percent = watch_time_average_percent
        self.watch_time = watch_time