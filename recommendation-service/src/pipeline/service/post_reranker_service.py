import logging
from uuid import UUID

from pipeline.model.ranking.normalized_post_ranking_features import NormalizedPostRankingFeatures
from pipeline.config import constants

logger = logging.getLogger(__name__)

class PostRerankerService:
    def rerank(self, user_id: UUID, posts: list[NormalizedPostRankingFeatures]) -> list[UUID]:
        if not posts:
            logger.warning(f"Reranker received empty post list for user {user_id}")
            return []
            
        logger.debug(f"Proceeding to rerank {len(posts)} posts for user {user_id}")
        
        posts.sort(key=self._calculate_hook_score, reverse=True)
        
        final_posts = self._apply_diversity_rule(posts)
        
        return [p.post_id for p in final_posts]

    def _calculate_hook_score(self, p: NormalizedPostRankingFeatures) -> float:
        score = 0.0
        score += p.follows_creator * constants.RERANKING_WEIGHT_FOLLOWS_CREATOR
        score += p.creator_affinity_score * constants.RERANKING_WEIGHT_CREATOR_AFFINITY
        score += p.tags_affinity_score * constants.RERANKING_WEIGHT_TAGS_AFFINITY
        score += p.freshness_score * constants.RERANKING_WEIGHT_FRESHNESS
        score += p.watch_time_average_percent * constants.RERANKING_WEIGHT_WATCH_TIME
        score += p.views_engagement * constants.RERANKING_WEIGHT_VIEWS_ENGAGEMENT
        return score

    def _apply_diversity_rule(self, posts: list[NormalizedPostRankingFeatures]) -> list[NormalizedPostRankingFeatures]:
        final_posts = []
        remaining = posts.copy()
        
        while remaining:
            valid_idx = -1
            
            # Find the first post in remaining that doesn't violate the rule
            for i, p in enumerate(remaining):
                if len(final_posts) >= 2 and final_posts[-1].creator_id == p.creator_id and final_posts[-2].creator_id == p.creator_id:
                    continue  # Rule violated, look at the next candidate
                
                # Found a valid candidate!
                valid_idx = i
                break
                
            if valid_idx != -1:
                # Pop the valid post and append it to our final list
                best_valid = remaining.pop(valid_idx)
                final_posts.append(best_valid)
            else:
                # If we get here, it means all remaining posts are from the exact same creator
                # that we just showed twice in a row. We have no other content to mix in.
                # just append the rest.
                final_posts.extend(remaining)
                break
                
        return final_posts
