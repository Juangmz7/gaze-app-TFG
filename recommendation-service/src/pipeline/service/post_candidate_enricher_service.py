import logging
from typing import Dict, List, Set
from uuid import UUID

from follow.repository.follow_repository import FollowRepository
from pipeline.model.interaction.candidate import Candidate
from pipeline.model.interaction.enriched_post_candidate import EnrichedPostCandidate
from pipeline.model.post.post_features import PostFeatures
from pipeline.model.post.post_interaction_features import PostInteractionFeatures
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_interaction_features_repository import PostInteractionFeaturesRepository
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository

logger = logging.getLogger(__name__)


class PostCandidateEnricherService:
    def __init__(
            self,
            post_features_repository: PostFeaturesRepository,
            post_interaction_features_repository: PostInteractionFeaturesRepository,
            user_creator_features_repository: UserCreatorFeaturesRepository,
            post_tag_features_repository: PostTagFeaturesRepository,
            follow_repository: FollowRepository,
    ):
        self.post_features_repository = post_features_repository
        self.post_interaction_features_repository = post_interaction_features_repository
        self.user_creator_features_repository = user_creator_features_repository
        self.post_tag_features_repository = post_tag_features_repository
        self.follow_repository = follow_repository

    def enrich(self, user_id: UUID, candidates: list[Candidate]) -> list[EnrichedPostCandidate]:
        if not candidates:
            return []

        logger.debug("Enriching %s candidates for user_id=%s", len(candidates), user_id)

        post_ids = [c.post_id for c in candidates]

        post_features_map = self._fetch_post_features(post_ids)
        post_interaction_features_map = self._fetch_post_interaction_features(post_ids)

        creator_ids = {pf.creator_id for pf in post_features_map.values()}
        all_posts_tags = set()
        for pf in post_features_map.values():
            all_posts_tags.update(pf.tags)

        user_creator_features_map = self._fetch_user_creator_features(user_id, list(creator_ids))
        user_tag_features_map = self._fetch_user_tag_features(user_id, list(all_posts_tags))
        followed_creator_ids = self._fetch_followed_creators(user_id, list(creator_ids))

        return self._build_enriched_candidates(
            candidates,
            post_features_map,
            post_interaction_features_map,
            user_creator_features_map,
            user_tag_features_map,
            followed_creator_ids,
        )

    def _fetch_post_features(self, post_ids: list[UUID]) -> dict[UUID, PostFeatures]:
        post_features_list = self.post_features_repository.get_post_features_batch(post_ids)
        return {pf.post_id: pf for pf in post_features_list}

    def _fetch_post_interaction_features(self, post_ids: list[UUID]) -> dict[UUID, PostInteractionFeatures]:
        post_interaction_features_list = self.post_interaction_features_repository.get_batch(post_ids)
        return {pif.post_id: pif for pif in post_interaction_features_list}

    def _fetch_user_creator_features(self, user_id: UUID, creator_ids: list[UUID]) -> dict[UUID, UserCreatorFeatures]:
        user_creator_features_list = self.user_creator_features_repository.get_batch(user_id, creator_ids)
        return {ucf.creator_id: ucf for ucf in user_creator_features_list}

    def _fetch_user_tag_features(self, user_id: UUID, all_tags: list[str]) -> dict[str, PostTagFeatures]:
        user_tag_features_list = self.post_tag_features_repository.get_post_tag_features(user_id, all_tags)
        return {ptf.tag_name: ptf for ptf in user_tag_features_list}

    def _fetch_followed_creators(self, user_id: UUID, creator_ids: list[UUID]) -> set[UUID]:
        return self.follow_repository.is_following_batch(user_id, creator_ids)

    def _build_enriched_candidates(
        self,
        candidates: list[Candidate],
        post_features_map: dict[UUID, PostFeatures],
        post_interaction_features_map: dict[UUID, PostInteractionFeatures],
        user_creator_features_map: dict[UUID, UserCreatorFeatures],
        user_tag_features_map: dict[str, PostTagFeatures],
        followed_creator_ids: set[UUID],
    ) -> list[EnrichedPostCandidate]:
        
        enriched_candidates = []
        for candidate in candidates:
            pf = post_features_map.get(candidate.post_id)
            pif = post_interaction_features_map.get(candidate.post_id)
            
            if not pf or not pif:
                logger.warning("Dropping candidate post_id=%s due to missing features", candidate.post_id)
                continue
                
            ucf = user_creator_features_map.get(pf.creator_id)
            follows_creator = pf.creator_id in followed_creator_ids
            creator_affinity_score = ucf.affinity_score if ucf else 0.0
            
            tags_affinity_score = []
            for tag in pf.tags:
                ptf = user_tag_features_map.get(tag)
                tags_affinity_score.append(ptf.affinity_score if ptf else 0.0)

            enriched = EnrichedPostCandidate(
                post_id=candidate.post_id,
                creator_id=pf.creator_id,
                impressions=pif.raw_interaction_stats.impressions,
                views=pif.raw_interaction_stats.views,
                likes=pif.raw_interaction_stats.likes,
                comments=pif.raw_interaction_stats.comments,
                shares=pif.raw_interaction_stats.shares,
                fast_skips=pif.raw_interaction_stats.fast_skips,
                collab_requests=pif.raw_interaction_stats.collab_requests,
                watch_time_average_percent=pif.raw_interaction_stats.watch_time_average_percent,
                decayed_impressions=pif.decayed_interaction_stats.impressions,
                views_engagement=pif.decayed_interaction_stats.views_engagement,
                decayed_likes=pif.decayed_interaction_stats.likes,
                decayed_comments=pif.decayed_interaction_stats.comments,
                decayed_shares=pif.decayed_interaction_stats.shares,
                decayed_fast_skips=pif.decayed_interaction_stats.fast_skips,
                decayed_collab_requests=pif.decayed_interaction_stats.collab_requests,
                last_decay_applied_at=pif.last_updated_at,
                follows_creator=follows_creator,
                creator_affinity_score=creator_affinity_score,
                tags_affinity_score=tags_affinity_score,
                retrieved_source=candidate.source,
                retrieved_source_score=candidate.score,
                created_at=pf.created_at,
            )
            enriched_candidates.append(enriched)

        return enriched_candidates
