from block.service.block_service import BlockService
from block.usecase.create_block_usecase import CreateBlockUsecase
from block.usecase.delete_block_usecase import DeleteBlockUsecase
from follow.service.follow_service import FollowService
from follow.usecase.create_follow_usecase import CreateFollowUsecase
from follow.usecase.delete_follow_usecase import DeleteFollowUsecase
from block.repository.impl.sql_alchemy_block_repository import SqlAlchemyBlockRepository
from follow.repository.impl.sql_alchemy_follow_repository import SqlAlchemyFollowRepository
from pipeline.repository.impl.sql_alchemy_post_features_repository import (
    SqlAlchemyPostFeaturesRepository,
)
from pipeline.repository.impl.sql_alchemy_post_tag_features_repository import (
    SqlAlchemyPostTagFeaturesRepository,
)
from pipeline.repository.impl.sql_alchemy_user_creator_features_repository import (
    SqlAlchemyUserCreatorFeaturesRepository,
)
from pipeline.repository.impl.sql_alchemy_user_features_repository import (
    SqlAlchemyUserFeaturesRepository,
)
from pipeline.service.semantic_embedding_model_service import SemanticEmbeddingModelService
from post.repository.impl.sql_alchemy_collab_repository import SqlAlchemyCollabRepository
from post.repository.impl.sql_alchemy_comment_post_repository import (
    SqlAlchemyCommentPostRepository,
)
from post.repository.impl.sql_alchemy_user_post_comment_interaction_repository import (
    SqlAlchemyUserPostCommentInteractionRepository,
)
from post.repository.impl.sql_alchemy_user_post_interactions_repository import (
    SqlAlchemyUserPostInteractionsRepository,
)
from shared.config.database import (
    SQLAlchemySessionProvider,
    SQLAlchemyTransactionManager,
)
from shared.repository.impl.sql_alchemy_processed_events_repository import (
    SqlAlchemyProcessedEventsRepository,
)
from post.usecase.ban_post_usecase import BanPostUsecase
from post.usecase.create_post_collab_request_usecase import CreatePostCollabRequestUsecase
from post.usecase.create_post_collab_usecase import CreatePostCollabUsecase
from post.usecase.create_post_comment_like_usecase import CreatePostCommentLikeUsecase
from post.usecase.create_post_comment_usecase import CreatePostCommentUsecase
from post.usecase.create_post_like_usecase import CreatePostLikeUsecase
from post.usecase.create_post_share_usecase import CreatePostShareUsecase
from post.usecase.create_post_usecase import CreatePostUsecase
from post.usecase.delete_post_collab_request_usecase import DeletePostCollabRequestUsecase
from post.usecase.delete_post_collab_usecase import DeletePostCollabUsecase
from post.usecase.delete_post_comment_like_usecase import DeletePostCommentLikeUsecase
from post.usecase.delete_post_comment_usecase import DeletePostCommentUsecase
from post.usecase.delete_post_like_usecase import DeletePostLikeUsecase
from post.usecase.delete_post_share_usecase import DeletePostShareUsecase
from post.usecase.delete_post_usecase import DeletePostUsecase
from post.usecase.exhaust_post_feed_usecase import ExhaustPostFeedUsecase
from post.usecase.link_post_collab_usecase import LinkPostCollabUsecase
from post.usecase.post_interaction_updater import PostInteractionUpdater
from post.usecase.register_post_view_usecase import RegisterPostViewUsecase
from post.usecase.update_post_usecase import UpdatePostUsecase
from rabbitmq.config.constants import PostRoutingKey, UserRoutingKey
from rabbitmq.event.post.post_events import (
    PostBannedEvent,
    PostCollabCreatedEvent,
    PostCollabDeletedEvent,
    PostCollabLinkedEvent,
    PostCollabRequestCreatedEvent,
    PostCollabRequestDeletedEvent,
    PostCommentCreatedEvent,
    PostCommentDeletedEvent,
    PostCommentLikeCreatedEvent,
    PostCommentLikeDeletedEvent,
    PostCreatedEvent,
    PostDeletedEvent,
    PostFeedExhaustedEvent,
    PostLikeCreatedEvent,
    PostLikeDeletedEvent,
    PostShareCreatedEvent,
    PostShareDeletedEvent,
    PostUpdatedEvent,
    PostViewedEvent,
)
from rabbitmq.event.user.user_events import (
    UserBlockCreatedEvent,
    UserBlockDeletedEvent,
    UserDeletedEvent,
    UserFollowCreatedEvent,
    UserFollowDeletedEvent,
    UserRegisteredEvent,
    UserUpdatedEvent,
)
from rabbitmq.handler.post.post_banned_event_handler import PostBannedEventHandler
from rabbitmq.handler.post.post_collab_created_event_handler import PostCollabCreatedEventHandler
from rabbitmq.handler.post.post_collab_deleted_event_handler import PostCollabDeletedEventHandler
from rabbitmq.handler.post.post_collab_linked_event_handler import PostCollabLinkedEventHandler
from rabbitmq.handler.post.post_collab_request_created_event_handler import (
    PostCollabRequestCreatedEventHandler,
)
from rabbitmq.handler.post.post_collab_request_deleted_event_handler import (
    PostCollabRequestDeletedEventHandler,
)
from rabbitmq.handler.post.post_comment_created_event_handler import PostCommentCreatedEventHandler
from rabbitmq.handler.post.post_comment_deleted_event_handler import PostCommentDeletedEventHandler
from rabbitmq.handler.post.post_comment_like_created_event_handler import (
    PostCommentLikeCreatedEventHandler,
)
from rabbitmq.handler.post.post_comment_like_deleted_event_handler import (
    PostCommentLikeDeletedEventHandler,
)
from rabbitmq.handler.post.post_created_event_handler import PostCreatedEventHandler
from rabbitmq.handler.post.post_deleted_event_handler import PostDeletedEventHandler
from rabbitmq.handler.post.post_feed_exhausted_event_handler import PostFeedExhaustedEventHandler
from rabbitmq.handler.post.post_like_created_event_handler import PostLikeCreatedEventHandler
from rabbitmq.handler.post.post_like_deleted_event_handler import PostLikeDeletedEventHandler
from rabbitmq.handler.post.post_share_created_event_handler import PostShareCreatedEventHandler
from rabbitmq.handler.post.post_share_deleted_event_handler import PostShareDeletedEventHandler
from rabbitmq.handler.post.post_updated_event_handler import PostUpdatedEventHandler
from rabbitmq.handler.post.post_viewed_event_handler import PostViewedEventHandler
from rabbitmq.handler.user.block_created_event_handler import BlockCreatedEventHandler
from rabbitmq.handler.user.block_deleted_event_handler import BlockDeletedEventHandler
from rabbitmq.handler.user.follow_created_event_handler import FollowCreatedEventHandler
from rabbitmq.handler.user.follow_deleted_event_handler import FollowDeletedEventHandler
from rabbitmq.handler.user.user_deleted_event_handler import UserDeletedEventHandler
from rabbitmq.handler.user.user_registered_event_handler import UserRegisteredEventHandler
from rabbitmq.handler.user.user_updated_event_handler import UserUpdatedEventHandler
from rabbitmq.listener.post_event_listener import PostEventListener
from rabbitmq.listener.user_event_listener import UserEventListener
from user.usecase.delete_user_usecase import DeleteUserUsecase
from user.usecase.register_user_usecase import RegisterUserUsecase
from user.usecase.update_user_usecase import UpdateUserUsecase


class Container:
    def __init__(self):
        self.session_provider = SQLAlchemySessionProvider()
        self.transaction_manager = SQLAlchemyTransactionManager()

        self.processed_events_repository = SqlAlchemyProcessedEventsRepository(self.session_provider)

        self.follow_repository = SqlAlchemyFollowRepository(self.session_provider)
        self.block_repository = SqlAlchemyBlockRepository(self.session_provider)
        self.post_features_repository = SqlAlchemyPostFeaturesRepository(self.session_provider)
        self.post_tag_features_repository = SqlAlchemyPostTagFeaturesRepository(self.session_provider)
        self.semantic_embedding_model_service = SemanticEmbeddingModelService()
        self.user_creator_features_repository = SqlAlchemyUserCreatorFeaturesRepository(
            self.session_provider
        )
        self.user_features_repository = SqlAlchemyUserFeaturesRepository(self.session_provider)
        self.collab_repository = SqlAlchemyCollabRepository(self.session_provider)
        self.comment_post_repository = SqlAlchemyCommentPostRepository(self.session_provider)
        self.user_post_interactions_repository = SqlAlchemyUserPostInteractionsRepository(
            self.session_provider
        )
        self.user_post_comment_interaction_repository = (
            SqlAlchemyUserPostCommentInteractionRepository(self.session_provider)
        )

        self.follow_service = FollowService(self.follow_repository)
        self.block_service = BlockService(self.block_repository, self.follow_service)
        self.post_interaction_updater = PostInteractionUpdater(
            user_creator_features_repository=self.user_creator_features_repository,
            post_tag_features_repository=self.post_tag_features_repository,
            user_features_repository=self.user_features_repository,
            post_features_repository=self.post_features_repository,
        )

        self.create_follow_usecase = CreateFollowUsecase(self.follow_service)
        self.delete_follow_usecase = DeleteFollowUsecase(self.follow_service)
        self.create_block_usecase = CreateBlockUsecase(self.block_service)
        self.delete_block_usecase = DeleteBlockUsecase(self.block_service)
        self.register_user_usecase = RegisterUserUsecase()
        self.update_user_usecase = UpdateUserUsecase()
        self.delete_user_usecase = DeleteUserUsecase()

        self.create_post_usecase = CreatePostUsecase(
            self.post_features_repository,
            self.semantic_embedding_model_service,
        )
        self.update_post_usecase = UpdatePostUsecase(
            self.post_features_repository,
            self.semantic_embedding_model_service,
            self.collab_repository,
        )
        self.delete_post_usecase = DeletePostUsecase(self.post_features_repository)
        self.ban_post_usecase = BanPostUsecase()
        self.exhaust_post_feed_usecase = ExhaustPostFeedUsecase()
        self.register_post_view_usecase = RegisterPostViewUsecase(
            self.user_creator_features_repository,
            self.post_tag_features_repository,
            self.post_features_repository,
        )
        self.create_post_collab_usecase = CreatePostCollabUsecase(
            self.collab_repository,
            self.post_features_repository,
            self.semantic_embedding_model_service,
        )
        self.link_post_collab_usecase = LinkPostCollabUsecase(
            self.collab_repository,
            self.post_features_repository,
            self.semantic_embedding_model_service,
        )
        self.delete_post_collab_usecase = DeletePostCollabUsecase(
            self.collab_repository,
            self.post_features_repository,
        )
        self.create_post_like_usecase = CreatePostLikeUsecase(
            self.post_interaction_updater,
            self.user_post_interactions_repository,
        )
        self.delete_post_like_usecase = DeletePostLikeUsecase(
            self.post_interaction_updater,
            self.user_post_interactions_repository,
        )
        self.create_post_share_usecase = CreatePostShareUsecase(
            self.post_interaction_updater,
            self.user_post_interactions_repository,
        )
        self.delete_post_share_usecase = DeletePostShareUsecase(
            self.post_interaction_updater,
            self.user_post_interactions_repository,
        )
        self.create_post_comment_usecase = CreatePostCommentUsecase(
            self.post_interaction_updater,
            self.user_post_interactions_repository,
            self.comment_post_repository,
        )
        self.delete_post_comment_usecase = DeletePostCommentUsecase(
            self.post_interaction_updater,
            self.user_post_interactions_repository,
            self.comment_post_repository,
        )
        self.create_post_comment_like_usecase = CreatePostCommentLikeUsecase(
            self.post_interaction_updater,
            self.user_post_comment_interaction_repository,
        )
        self.delete_post_comment_like_usecase = DeletePostCommentLikeUsecase(
            self.post_interaction_updater,
            self.user_post_comment_interaction_repository,
            self.comment_post_repository,
        )
        self.create_post_collab_request_usecase = CreatePostCollabRequestUsecase(
            self.post_interaction_updater,
            self.user_post_interactions_repository,
            self.collab_repository,
        )
        self.delete_post_collab_request_usecase = DeletePostCollabRequestUsecase(
            self.post_interaction_updater,
            self.user_post_interactions_repository,
            self.collab_repository,
        )

        self.follow_created_event_handler = FollowCreatedEventHandler(self.create_follow_usecase)
        self.follow_deleted_event_handler = FollowDeletedEventHandler(self.delete_follow_usecase)
        self.block_created_event_handler = BlockCreatedEventHandler(self.create_block_usecase)
        self.block_deleted_event_handler = BlockDeletedEventHandler(self.delete_block_usecase)
        self.user_registered_event_handler = UserRegisteredEventHandler(self.register_user_usecase)
        self.user_updated_event_handler = UserUpdatedEventHandler(self.update_user_usecase)
        self.user_deleted_event_handler = UserDeletedEventHandler(self.delete_user_usecase)

        self.post_share_deleted_event_handler = PostShareDeletedEventHandler(
            self.delete_post_share_usecase
        )
        self.post_share_created_event_handler = PostShareCreatedEventHandler(
            self.create_post_share_usecase
        )
        self.post_collab_created_event_handler = PostCollabCreatedEventHandler(
            self.create_post_collab_usecase
        )
        self.post_collab_linked_event_handler = PostCollabLinkedEventHandler(
            self.link_post_collab_usecase
        )
        self.post_collab_deleted_event_handler = PostCollabDeletedEventHandler(
            self.delete_post_collab_usecase
        )
        self.post_collab_request_created_event_handler = PostCollabRequestCreatedEventHandler(
            self.create_post_collab_request_usecase
        )
        self.post_collab_request_deleted_event_handler = PostCollabRequestDeletedEventHandler(
            self.delete_post_collab_request_usecase
        )
        self.post_comment_like_deleted_event_handler = PostCommentLikeDeletedEventHandler(
            self.delete_post_comment_like_usecase
        )
        self.post_comment_like_created_event_handler = PostCommentLikeCreatedEventHandler(
            self.create_post_comment_like_usecase
        )
        self.post_comment_deleted_event_handler = PostCommentDeletedEventHandler(
            self.delete_post_comment_usecase
        )
        self.post_comment_created_event_handler = PostCommentCreatedEventHandler(
            self.create_post_comment_usecase
        )
        self.post_viewed_event_handler = PostViewedEventHandler(self.register_post_view_usecase)
        self.post_like_deleted_event_handler = PostLikeDeletedEventHandler(
            self.delete_post_like_usecase
        )
        self.post_like_created_event_handler = PostLikeCreatedEventHandler(
            self.create_post_like_usecase
        )
        self.post_banned_event_handler = PostBannedEventHandler(self.ban_post_usecase)
        self.post_deleted_event_handler = PostDeletedEventHandler(self.delete_post_usecase)
        self.post_updated_event_handler = PostUpdatedEventHandler(self.update_post_usecase)
        self.post_created_event_handler = PostCreatedEventHandler(self.create_post_usecase)
        self.post_feed_exhausted_event_handler = PostFeedExhaustedEventHandler(
            self.exhaust_post_feed_usecase
        )

        self.user_event_handlers = {
            UserRoutingKey.FOLLOW_DELETED: (
                UserFollowDeletedEvent,
                self.follow_deleted_event_handler,
            ),
            UserRoutingKey.FOLLOW_CREATED: (
                UserFollowCreatedEvent,
                self.follow_created_event_handler,
            ),
            UserRoutingKey.BLOCK_DELETED: (
                UserBlockDeletedEvent,
                self.block_deleted_event_handler,
            ),
            UserRoutingKey.DELETED: (
                UserDeletedEvent,
                self.user_deleted_event_handler,
            ),
            UserRoutingKey.BLOCK_CREATED: (
                UserBlockCreatedEvent,
                self.block_created_event_handler,
            ),
            UserRoutingKey.REGISTERED: (
                UserRegisteredEvent,
                self.user_registered_event_handler,
            ),
            UserRoutingKey.UPDATED: (
                UserUpdatedEvent,
                self.user_updated_event_handler,
            ),
        }
        self.post_event_handlers = {
            PostRoutingKey.SHARE_DELETED: (
                PostShareDeletedEvent,
                self.post_share_deleted_event_handler,
            ),
            PostRoutingKey.SHARE_CREATED: (
                PostShareCreatedEvent,
                self.post_share_created_event_handler,
            ),
            PostRoutingKey.COLLAB_CREATED: (
                PostCollabCreatedEvent,
                self.post_collab_created_event_handler,
            ),
            PostRoutingKey.COLLAB_LINKED: (
                PostCollabLinkedEvent,
                self.post_collab_linked_event_handler,
            ),
            PostRoutingKey.COLLAB_DELETED: (
                PostCollabDeletedEvent,
                self.post_collab_deleted_event_handler,
            ),
            PostRoutingKey.COLLAB_REQUEST_CREATED: (
                PostCollabRequestCreatedEvent,
                self.post_collab_request_created_event_handler,
            ),
            PostRoutingKey.COLLAB_REQUEST_DELETED: (
                PostCollabRequestDeletedEvent,
                self.post_collab_request_deleted_event_handler,
            ),
            PostRoutingKey.COMMENT_LIKE_DELETED: (
                PostCommentLikeDeletedEvent,
                self.post_comment_like_deleted_event_handler,
            ),
            PostRoutingKey.COMMENT_LIKE_CREATED: (
                PostCommentLikeCreatedEvent,
                self.post_comment_like_created_event_handler,
            ),
            PostRoutingKey.COMMENT_DELETED: (
                PostCommentDeletedEvent,
                self.post_comment_deleted_event_handler,
            ),
            PostRoutingKey.COMMENT_CREATED: (
                PostCommentCreatedEvent,
                self.post_comment_created_event_handler,
            ),
            PostRoutingKey.VIEWED: (
                PostViewedEvent,
                self.post_viewed_event_handler,
            ),
            PostRoutingKey.LIKE_DELETED: (
                PostLikeDeletedEvent,
                self.post_like_deleted_event_handler,
            ),
            PostRoutingKey.LIKE_CREATED: (
                PostLikeCreatedEvent,
                self.post_like_created_event_handler,
            ),
            PostRoutingKey.BANNED: (
                PostBannedEvent,
                self.post_banned_event_handler,
            ),
            PostRoutingKey.DELETED: (
                PostDeletedEvent,
                self.post_deleted_event_handler,
            ),
            PostRoutingKey.UPDATED: (
                PostUpdatedEvent,
                self.post_updated_event_handler,
            ),
            PostRoutingKey.CREATED: (
                PostCreatedEvent,
                self.post_created_event_handler,
            ),
            PostRoutingKey.FEED_EXHAUSTED: (
                PostFeedExhaustedEvent,
                self.post_feed_exhausted_event_handler,
            ),
        }

        self.user_event_listener = UserEventListener(
            self.processed_events_repository,
            self.user_event_handlers,
            self.transaction_manager,
        )
        self.post_event_listener = PostEventListener(
            self.processed_events_repository,
            self.post_event_handlers,
            self.transaction_manager,
        )


container = Container()
