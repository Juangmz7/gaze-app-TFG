from faststream.rabbit import RabbitQueue

from block.handlers.block_handlers import BlockCreatedHandler, BlockDeletedHandler
from block.usecase import CreateBlockUsecase, DeleteBlockUsecase
from follow.handlers.follow_handlers import FollowCreatedHandler, FollowDeletedHandler
from follow.usecase import CreateFollowUsecase, DeleteFollowUsecase
from pipeline.handlers.interaction_handlers import (
    PostCollabRequestCreatedHandler,
    PostCollabRequestDeletedHandler,
    PostCommentCreatedHandler,
    PostCommentDeletedHandler,
    PostCommentLikeCreatedHandler,
    PostCommentLikeDeletedHandler,
    PostLikeCreatedHandler,
    PostLikeDeletedHandler,
    PostShareCreatedHandler,
    PostShareDeletedHandler,
    PostViewedHandler,
)
from pipeline.handlers.post_handlers import (
    PostBannedHandler,
    PostCreatedHandler,
    PostDeletedHandler,
    PostFeedExhaustedHandler,
    PostUpdatedHandler,
)
from pipeline.handlers.user_handlers import (
    UserDeletedHandler,
    UserRegisteredHandler,
    UserUpdatedHandler,
)
from pipeline.usecase.interaction_usecases import (
    CreatePostCollabRequestUsecase,
    CreatePostCommentLikeUsecase,
    CreatePostCommentUsecase,
    CreatePostLikeUsecase,
    CreatePostShareUsecase,
    CreatePostViewUsecase,
    DeletePostCollabRequestUsecase,
    DeletePostCommentLikeUsecase,
    DeletePostCommentUsecase,
    DeletePostLikeUsecase,
    DeletePostShareUsecase,
)
from pipeline.usecase.post_usecases import (
    BanPostUsecase,
    CreatePostUsecase,
    DeletePostUsecase,
    FeedExhaustedUsecase,
    UpdatePostUsecase,
)
from pipeline.usecase.user_usecases import (
    DeleteUserUsecase,
    RegisterUserUsecase,
    UpdateUserUsecase,
)
from shared.repository.processed_events_repository import ProcessedEventsRepository
from rabbitmq.config.constants import (
    POST_QUEUE,
    USER_QUEUE,
)
from rabbitmq.config.connection import broker
from rabbitmq.listener.post_event_listener import PostEventListener
from rabbitmq.listener.user_event_listener import UserEventListener


processed_events_repository = ProcessedEventsRepository()

user_event_handler = UserEventListener(
    processed_events_repository=processed_events_repository,
    follow_deleted_handler=FollowDeletedHandler(DeleteFollowUsecase()),
    follow_created_handler=FollowCreatedHandler(CreateFollowUsecase()),
    block_deleted_handler=BlockDeletedHandler(DeleteBlockUsecase()),
    user_deleted_handler=UserDeletedHandler(DeleteUserUsecase()),
    block_created_handler=BlockCreatedHandler(CreateBlockUsecase()),
    user_registered_handler=UserRegisteredHandler(RegisterUserUsecase()),
    user_updated_handler=UserUpdatedHandler(UpdateUserUsecase()),
)

post_event_handler = PostEventListener(
    processed_events_repository=processed_events_repository,
    share_deleted_handler=PostShareDeletedHandler(DeletePostShareUsecase()),
    share_created_handler=PostShareCreatedHandler(CreatePostShareUsecase()),
    collab_request_created_handler=PostCollabRequestCreatedHandler(
        CreatePostCollabRequestUsecase()
    ),
    collab_request_deleted_handler=PostCollabRequestDeletedHandler(
        DeletePostCollabRequestUsecase()
    ),
    comment_like_deleted_handler=PostCommentLikeDeletedHandler(
        DeletePostCommentLikeUsecase()
    ),
    comment_like_created_handler=PostCommentLikeCreatedHandler(
        CreatePostCommentLikeUsecase()
    ),
    comment_deleted_handler=PostCommentDeletedHandler(DeletePostCommentUsecase()),
    comment_created_handler=PostCommentCreatedHandler(CreatePostCommentUsecase()),
    viewed_handler=PostViewedHandler(CreatePostViewUsecase()),
    like_deleted_handler=PostLikeDeletedHandler(DeletePostLikeUsecase()),
    like_created_handler=PostLikeCreatedHandler(CreatePostLikeUsecase()),
    banned_handler=PostBannedHandler(BanPostUsecase()),
    deleted_handler=PostDeletedHandler(DeletePostUsecase()),
    updated_handler=PostUpdatedHandler(UpdatePostUsecase()),
    created_handler=PostCreatedHandler(CreatePostUsecase()),
    feed_exhausted_handler=PostFeedExhaustedHandler(FeedExhaustedUsecase()),
)


broker.subscriber(
    RabbitQueue(
        USER_QUEUE,
        declare=False,
    )
)(user_event_handler.handle_event)


broker.subscriber(
    RabbitQueue(
        POST_QUEUE,
        declare=False,
    )
)(post_event_handler.handle_event)
