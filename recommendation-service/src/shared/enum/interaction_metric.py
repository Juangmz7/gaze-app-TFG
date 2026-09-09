from enum import StrEnum


class InteractionMetric(StrEnum):
    IMPRESSIONS = "impressions"
    VIEWS = "views"
    VIEW_ENGAGEMENT = "views_engagement"
    LIKES = "likes"
    COMMENTS = "comments"
    COMMENT_LIKES = "comments_likes"
    SHARES = "shares"
    FAST_SKIPS = "fast_skips"
    COLLAB_REQUESTS = "collab_requests"
    COLLAB_REQUESTS_ACCEPTED = "collab_requests_accepted"
    WATCH_TIME_AVERAGE_PERCENT = "watch_time_average_percent"
    WATCH_TIME = "watch_time"

    @property
    def raw_stats_attribute(self) -> str:
        if self is InteractionMetric.VIEW_ENGAGEMENT:
            return "views"

        return self._stats_attribute

    @property
    def decayed_stats_attribute(self) -> str:
        if self is InteractionMetric.VIEWS:
            return "views_engagement"

        return self._stats_attribute

    @property
    def _stats_attribute(self) -> str:
        if self is InteractionMetric.COMMENT_LIKES:
            return "commentsLikes"

        return self.value
