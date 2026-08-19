from typing import Any

from pydantic import RootModel


class PostShareDeletedEvent(RootModel[dict[str, Any]]):
    pass


class PostShareCreatedEvent(RootModel[dict[str, Any]]):
    pass


class PostCollabRequestCreatedEvent(RootModel[dict[str, Any]]):
    pass


class PostCollabRequestDeletedEvent(RootModel[dict[str, Any]]):
    pass


class PostCommentLikeDeletedEvent(RootModel[dict[str, Any]]):
    pass


class PostCommentLikeCreatedEvent(RootModel[dict[str, Any]]):
    pass


class PostCommentDeletedEvent(RootModel[dict[str, Any]]):
    pass


class PostCommentCreatedEvent(RootModel[dict[str, Any]]):
    pass


class PostViewedEvent(RootModel[dict[str, Any]]):
    pass


class PostLikeDeletedEvent(RootModel[dict[str, Any]]):
    pass


class PostLikeCreatedEvent(RootModel[dict[str, Any]]):
    pass


class PostBannedEvent(RootModel[dict[str, Any]]):
    pass


class PostDeletedEvent(RootModel[dict[str, Any]]):
    pass


class PostUpdatedEvent(RootModel[dict[str, Any]]):
    pass


class PostCreatedEvent(RootModel[dict[str, Any]]):
    pass


class PostFeedExhaustedEvent(RootModel[dict[str, Any]]):
    pass