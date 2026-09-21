import dataclasses
from typing import List
from pipeline.model.interaction.enriched_post_candidate import EnrichedPostCandidate
from pipeline.service.candidate_normalizer import CandidateNormalizer

class MinMaxCandidateNormalizer(CandidateNormalizer):
    def normalize(self, candidates: List[EnrichedPostCandidate]) -> List[EnrichedPostCandidate]:
        if not candidates:
            return []

        # Find min and max for numerical attributes
        fields_to_normalize = [
            'impressions', 'views', 'likes', 'comments', 'shares', 'fast_skips', 'collab_requests', 
            'watch_time_average_percent', 'decayed_impressions', 'views_engagement', 'decayed_likes', 
            'decayed_comments', 'decayed_shares', 'decayed_fast_skips', 'decayed_collab_requests',
            'creator_afinity_score', 'retrieved_source_score'
        ]

        min_vals = {}
        max_vals = {}

        for field in fields_to_normalize:
            min_vals[field] = min(getattr(c, field) for c in candidates)
            max_vals[field] = max(getattr(c, field) for c in candidates)

        all_tags = [tag for c in candidates for tag in c.tags_affinity_score]
        min_tags = min(all_tags) if all_tags else 0.0
        max_tags = max(all_tags) if all_tags else 0.0

        normalized_candidates = []
        for c in candidates:
            new_kwargs = {}
            for field in fields_to_normalize:
                val = getattr(c, field)
                min_v = min_vals[field]
                max_v = max_vals[field]
                if max_v > min_v:
                    new_val = (val - min_v) / (max_v - min_v)
                else:
                    new_val = 0.0
                new_kwargs[field] = float(new_val)

            new_tags = []
            for tag in c.tags_affinity_score:
                if max_tags > min_tags:
                    new_tags.append((tag - min_tags) / (max_tags - min_tags))
                else:
                    new_tags.append(0.0)
            new_kwargs['tags_affinity_score'] = new_tags

            normalized_candidates.append(dataclasses.replace(c, **new_kwargs))
            
        return normalized_candidates
