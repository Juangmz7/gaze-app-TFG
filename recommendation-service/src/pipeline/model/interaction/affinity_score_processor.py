from pipeline.config.constants import COLLAB_REQUEST_ACCEPT_WEIGHT, COLLAB_REQUEST_REQUEST_WEIGHT, FAST_SKIP_WEIGHT, POST_COMMENT_LIKE_WEIGHT, POST_COMMENT_WEIGHT, POST_LIKE_WEIGHT, POST_SHARE_WEIGHT, POST_VIEW_WEIGHT
from pipeline.model.interaction.decayed_interaction_stats import DecayedInteractionStats

class AffinityScoreProcessor:
    def __init__(self, interaction_stats: DecayedInteractionStats):
        self.interaction_stats = interaction_stats

    def calculate_affinity_score(self) -> float:
        stats = self.interaction_stats
        return (
            stats.views_engagement * POST_VIEW_WEIGHT +
            stats.likes * POST_LIKE_WEIGHT +
            stats.comments * POST_COMMENT_WEIGHT +
            stats.commentsLikes * POST_COMMENT_LIKE_WEIGHT +
            stats.shares * POST_SHARE_WEIGHT +
            stats.fast_skips * FAST_SKIP_WEIGHT +
            stats.collab_requests * COLLAB_REQUEST_REQUEST_WEIGHT +
            stats.collab_requests_accepted * COLLAB_REQUEST_ACCEPT_WEIGHT   
        )
    