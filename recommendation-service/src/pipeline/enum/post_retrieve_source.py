
from enum import StrEnum


class PostRetrieveSource(StrEnum):
    SEMANTIC = "semantic"
    COLLABORATIVE = "collaborative"
    POPULAR = "popular"
    RANDOM = "random"
    UNSEEN_TAG = "unseen_tag"