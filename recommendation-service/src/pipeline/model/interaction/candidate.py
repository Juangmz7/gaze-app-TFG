
from uuid import UUID

from pipeline.enum.post_retrieve_source import PostRetrieveSource


class Candidate():
    def __init__(self, post_id: UUID, source: PostRetrieveSource, score: float):
        self.post_id = post_id
        self.source = source
        self.score = score