from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class FeedRequestReason(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    FEED_REQUEST_REASON_UNSPECIFIED: _ClassVar[FeedRequestReason]
    DISCONNECT: _ClassVar[FeedRequestReason]
    STALE_HEARTBEAT: _ClassVar[FeedRequestReason]
    USER_REGISTERED: _ClassVar[FeedRequestReason]
    LOW_WATERMARK: _ClassVar[FeedRequestReason]
    FEED_EMPTY: _ClassVar[FeedRequestReason]
FEED_REQUEST_REASON_UNSPECIFIED: FeedRequestReason
DISCONNECT: FeedRequestReason
STALE_HEARTBEAT: FeedRequestReason
USER_REGISTERED: FeedRequestReason
LOW_WATERMARK: FeedRequestReason
FEED_EMPTY: FeedRequestReason

class GetRecommendedFeedRequest(_message.Message):
    __slots__ = ("user_id", "excluded_post_ids", "reason")
    USER_ID_FIELD_NUMBER: _ClassVar[int]
    EXCLUDED_POST_IDS_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    user_id: str
    excluded_post_ids: _containers.RepeatedScalarFieldContainer[str]
    reason: FeedRequestReason
    def __init__(self, user_id: _Optional[str] = ..., excluded_post_ids: _Optional[_Iterable[str]] = ..., reason: _Optional[_Union[FeedRequestReason, str]] = ...) -> None: ...

class GetRecommendedFeedResponse(_message.Message):
    __slots__ = ("post_ids",)
    POST_IDS_FIELD_NUMBER: _ClassVar[int]
    post_ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, post_ids: _Optional[_Iterable[str]] = ...) -> None: ...
