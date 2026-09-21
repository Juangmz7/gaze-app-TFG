from abc import ABC, abstractmethod
from typing import List
from pipeline.model.interaction.enriched_post_candidate import EnrichedPostCandidate

class CandidateNormalizer(ABC):
    @abstractmethod
    def normalize(self, candidates: List[EnrichedPostCandidate]) -> List[EnrichedPostCandidate]:
        pass
