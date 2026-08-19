from typing import Any

from pydantic import RootModel


class UserFollowDeletedEvent(RootModel[dict[str, Any]]):
    pass


class UserFollowCreatedEvent(RootModel[dict[str, Any]]):
    pass


class UserBlockDeletedEvent(RootModel[dict[str, Any]]):
    pass


class UserDeletedEvent(RootModel[dict[str, Any]]):
    pass


class UserBlockCreatedEvent(RootModel[dict[str, Any]]):
    pass


class UserRegisteredEvent(RootModel[dict[str, Any]]):
    pass


class UserUpdatedEvent(RootModel[dict[str, Any]]):
    pass