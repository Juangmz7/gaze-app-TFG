from abc import ABC, abstractmethod

from pipeline.model.interaction.enriched_post_candidate import EnrichedPostCandidate
from pipeline.model.ranking.normalized_post_ranking_features import NormalizedPostRankingFeatures

class CandidateNormalizer(ABC):
    @abstractmethod
    def normalize(self, candidates: list[EnrichedPostCandidate]) -> list[NormalizedPostRankingFeatures]:
        pass
