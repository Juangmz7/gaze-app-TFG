from pipeline.config.constants import (
    COLLAB_REQUEST_ACCEPT_WEIGHT,
    COLLAB_REQUEST_REQUEST_WEIGHT,
    FAST_SKIP_WEIGHT,
    POST_COMMENT_LIKE_WEIGHT,
    POST_COMMENT_WEIGHT,
    POST_LIKE_WEIGHT,
    POST_SHARE_WEIGHT,
    POST_VIEW_WEIGHT,
)
from pipeline.model.interaction.decayed_interaction_stats import (
    DecayedInteractionStats,
)


class AffinityScoreProcessor:
    def __init__(self, interaction_stats: DecayedInteractionStats):
        self.interaction_stats = interaction_stats

    def calculate_affinity_score(self) -> float:
        stats = self.interaction_stats

        if stats.impressions <= 0:
            content_affinity = 0.0
        ## Apply the stat rate and the weight to each stat and sum them up to get the content affinity score
        else:
            content_affinity = (
                (stats.views_engagement / stats.impressions) * POST_VIEW_WEIGHT
                + (stats.likes / stats.impressions) * POST_LIKE_WEIGHT
                + (stats.comments / stats.impressions) * POST_COMMENT_WEIGHT
                + (stats.commentsLikes / stats.impressions) * POST_COMMENT_LIKE_WEIGHT
                + (stats.shares / stats.impressions) * POST_SHARE_WEIGHT
                + (stats.fast_skips / stats.impressions) * FAST_SKIP_WEIGHT
            )

        social_affinity = (
            stats.collab_requests * COLLAB_REQUEST_REQUEST_WEIGHT
            + stats.collab_requests_accepted * COLLAB_REQUEST_ACCEPT_WEIGHT
        )

        return content_affinity + social_affinity