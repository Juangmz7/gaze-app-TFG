from uuid import UUID

from pipeline.enum.post_retrieve_source import PostRetrieveSource
from pipeline.model.interaction.candidate import Candidate
from pipeline.repository.explorative_post_retrieval_repository import ExplorativePostRetrievalRepository


POPULAR_POST_LIMIT = 30
RANDOM_POST_LIMIT = 40
UNSEEN_TAGS_POST_LIMIT = 30


class ExplorativePostRetrievalUsecase:

    def __init__(
        self,
        explorative_post_retrieval_repository: ExplorativePostRetrievalRepository,
    ):
        self.explorative_post_retrieval_repository = (
            explorative_post_retrieval_repository
        )

    def retrieve_posts(self, user_id: UUID, limit_multiplier: float = 1.0) -> list[Candidate]:
        candidates = []

        self._retrieve_popular_posts(user_id, candidates, limit_multiplier)
        self._retrieve_random_posts(user_id, candidates, limit_multiplier)
        self._retrieve_unseen_tag_posts(user_id, candidates, limit_multiplier)

        return candidates

    def retrieve_posts_for_new_user(self, user_id: UUID, limit_multiplier: float = 1.0) -> list[Candidate]:
        candidates = []

        self._retrieve_popular_posts(user_id, candidates, limit_multiplier)
        self._retrieve_random_posts(user_id, candidates, limit_multiplier)

        return candidates

    def _retrieve_popular_posts(
        self,
        user_id: UUID,
        candidates: list[Candidate],
        limit_multiplier: float = 1.0
    ) -> None:
        posts = self.explorative_post_retrieval_repository.get_popular_posts(
            user_id,
            int(POPULAR_POST_LIMIT * limit_multiplier),
        )

        if posts:
            for post_id, score in posts:
                candidates.append(
                    Candidate(
                        post_id,
                        PostRetrieveSource.POPULAR,
                        score,
                    )
                )

    def _retrieve_random_posts(
        self,
        user_id: UUID,
        candidates: list[Candidate],
        limit_multiplier: float = 1.0
    ) -> None:
        posts = self.explorative_post_retrieval_repository.get_random_posts(
            user_id,
            int(RANDOM_POST_LIMIT * limit_multiplier),
        )

        if posts:
            for post_id, score in posts:
                candidates.append(
                    Candidate(
                        post_id,
                        PostRetrieveSource.RANDOM,
                        score,
                    )
                )

    def _retrieve_unseen_tag_posts(
        self,
        user_id: UUID,
        candidates: list[Candidate],
        limit_multiplier: float = 1.0
    ) -> None:
        posts = self.explorative_post_retrieval_repository.get_unseen_tags_posts(
            user_id,
            int(UNSEEN_TAGS_POST_LIMIT * limit_multiplier),
        )

        if posts:
            for post_id, score in posts:
                candidates.append(
                    Candidate(
                        post_id,
                        PostRetrieveSource.UNSEEN_TAG,
                        score,
                    )
                )
