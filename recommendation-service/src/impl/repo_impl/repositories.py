from datetime import datetime, timezone
from typing import Optional
from uuid import UUID

from sqlalchemy import and_, delete, exists, insert, or_, select, update
from sqlalchemy.orm import Session

from block.model.block import Block
from block.repository.block_repository import BlockRepository
from follow.model.follow import Follow
from follow.repository.follow_repository import FollowRepository
from pipeline.model.post.post_features import PostFeatures
from pipeline.model.post.post_tag_features import PostTagFeatures
from pipeline.model.user.user_creator_features import UserCreatorFeatures
from pipeline.model.user.user_features import UserFeatures
from pipeline.repository.post_features_repository import PostFeaturesRepository
from pipeline.repository.post_tag_features_repository import PostTagFeaturesRepository
from pipeline.repository.user_creator_features_repository import UserCreatorFeaturesRepository
from pipeline.repository.user_features_repository import UserFeaturesRepository
from post.model.collab import Collab
from post.model.user_post_comment_interaction import UserPostCommentInteraction
from post.model.user_post_interactions import UserPostInteractions
from post.repository.collab_repository import CollabRepository
from post.repository.comment_post_repository import CommentPostRepository
from post.repository.user_post_comment_interaction_repository import (
    UserPostCommentInteractionRepository,
)
from post.repository.user_post_interactions_repository import UserPostInteractionsRepository
from shared.repository.processed_events_repository import ProcessedEventsRepository

from impl.repo_impl.database import SQLAlchemySessionProvider
from impl.repo_impl.mappers import (
    collab_from_record,
    collab_values,
    post_features_from_record,
    post_features_values,
    post_tag_features_from_record,
    post_tag_features_values,
    user_creator_features_from_record,
    user_creator_features_values,
    user_features_from_record,
    user_features_values,
    user_post_comment_interaction_from_record,
    user_post_comment_interaction_values,
    user_post_interactions_from_record,
    user_post_interactions_values,
)
from impl.repo_impl.models import (
    BlockRecord,
    CollabRecord,
    CommentPostRecord,
    FollowRecord,
    PostFeaturesRecord,
    PostTagFeaturesRecord,
    ProcessedEventRecord,
    UserCreatorFeaturesRecord,
    UserFeaturesRecord,
    UserPostCommentInteractionRecord,
    UserPostInteractionsRecord,
)


class BaseSqlAlchemyRepository:
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def _update_or_insert(
        self,
        session: Session,
        record_type,
        identity_filter,
        values: dict[str, object],
    ) -> None:
        result = session.execute(update(record_type).where(*identity_filter).values(**values))
        if result.rowcount == 0:
            session.execute(insert(record_type).values(**values))


class SqlAlchemyFollowRepository(BaseSqlAlchemyRepository, FollowRepository):
    def create_follow(self, follow: Follow) -> None:
        values = {
            "follower_id": follow.follower_id,
            "followed_id": follow.followed_id,
            "created_at": follow.created_at,
        }
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                FollowRecord,
                (
                    FollowRecord.follower_id == follow.follower_id,
                    FollowRecord.followed_id == follow.followed_id,
                ),
                values,
            )

    def remove_follow(self, follower_user_id: UUID, followed_user_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(
                delete(FollowRecord).where(
                    FollowRecord.follower_id == follower_user_id,
                    FollowRecord.followed_id == followed_user_id,
                )
            )

    def remove_follows_between_users(self, user_id_1: UUID, user_id_2: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(
                delete(FollowRecord).where(
                    or_(
                        and_(
                            FollowRecord.follower_id == user_id_1,
                            FollowRecord.followed_id == user_id_2,
                        ),
                        and_(
                            FollowRecord.follower_id == user_id_2,
                            FollowRecord.followed_id == user_id_1,
                        ),
                    )
                )
            )


class SqlAlchemyBlockRepository(BaseSqlAlchemyRepository, BlockRepository):
    def create_block(self, block: Block) -> None:
        values = {
            "blocker_id": block.blocker_id,
            "blocked_id": block.blocked_id,
            "created_at": block.created_at,
        }
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                BlockRecord,
                (
                    BlockRecord.blocker_id == block.blocker_id,
                    BlockRecord.blocked_id == block.blocked_id,
                ),
                values,
            )

    def remove_block(self, blocker_user_id: UUID, blocked_user_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(
                delete(BlockRecord).where(
                    BlockRecord.blocker_id == blocker_user_id,
                    BlockRecord.blocked_id == blocked_user_id,
                )
            )


class SqlAlchemyProcessedEventsRepository(
    BaseSqlAlchemyRepository,
    ProcessedEventsRepository,
):
    def isAlreadyProcessed(self, event_id: UUID, correlation_id: UUID) -> bool:
        with self.session_provider.session() as session:
            return bool(
                session.scalar(
                    select(
                        exists().where(
                            ProcessedEventRecord.event_id == event_id,
                            ProcessedEventRecord.correlation_id == correlation_id,
                        )
                    )
                )
            )

    def setEventAsProcessed(self, event_id: UUID, correlation_id: UUID, event_name: str) -> None:
        values = {
            "event_id": event_id,
            "correlation_id": correlation_id,
            "event_name": event_name,
            "processed_at": datetime.now(timezone.utc),
        }
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                ProcessedEventRecord,
                (
                    ProcessedEventRecord.event_id == event_id,
                    ProcessedEventRecord.correlation_id == correlation_id,
                ),
                values,
            )


class SqlAlchemyPostFeaturesRepository(BaseSqlAlchemyRepository, PostFeaturesRepository):
    def get_post_features(self, post_id: UUID) -> PostFeatures | None:
        with self.session_provider.session() as session:
            record = session.get(PostFeaturesRecord, post_id)
            return post_features_from_record(record) if record is not None else None

    def save(self, post_features: PostFeatures) -> None:
        values = post_features_values(post_features)
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                PostFeaturesRecord,
                (PostFeaturesRecord.post_id == post_features.post_id,),
                values,
            )

    def delete(self, post_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(delete(PostFeaturesRecord).where(PostFeaturesRecord.post_id == post_id))

    def update_post_collab(
        self,
        post_id: UUID,
        collab_id: Optional[UUID],
        collab_title: str | None,
    ) -> None:
        with self.session_provider.session() as session:
            session.execute(
                update(PostFeaturesRecord)
                .where(PostFeaturesRecord.post_id == post_id)
                .values(collab_id=collab_id, collab_title=collab_title)
            )


class SqlAlchemyPostTagFeaturesRepository(
    BaseSqlAlchemyRepository,
    PostTagFeaturesRepository,
):
    def getPostsTagsFeatures(self, user_id: UUID, tags: list[str]) -> list[PostTagFeatures]:
        if not tags:
            return []

        with self.session_provider.session() as session:
            records = session.scalars(
                select(PostTagFeaturesRecord).where(
                    PostTagFeaturesRecord.user_id == user_id,
                    PostTagFeaturesRecord.tag_name.in_(tags),
                )
            ).all()
            return [post_tag_features_from_record(record) for record in records]

    def save_all(self, post_tag_features: list[PostTagFeatures]) -> None:
        with self.session_provider.session() as session:
            for features in post_tag_features:
                values = post_tag_features_values(features)
                self._update_or_insert(
                    session,
                    PostTagFeaturesRecord,
                    (
                        PostTagFeaturesRecord.user_id == features.user_id,
                        PostTagFeaturesRecord.tag_name == features.tag_name,
                    ),
                    values,
                )


class SqlAlchemyUserCreatorFeaturesRepository(
    BaseSqlAlchemyRepository,
    UserCreatorFeaturesRepository,
):
    def get_user_creator_features(
        self,
        post_id: UUID,
        user_id: UUID,
    ) -> UserCreatorFeatures | None:
        with self.session_provider.session() as session:
            record = session.scalars(
                select(UserCreatorFeaturesRecord)
                .join(
                    PostFeaturesRecord,
                    PostFeaturesRecord.creator_id == UserCreatorFeaturesRecord.creator_id,
                )
                .where(
                    PostFeaturesRecord.post_id == post_id,
                    UserCreatorFeaturesRecord.user_id == user_id,
                )
                .limit(1)
            ).first()
            return user_creator_features_from_record(record) if record is not None else None

    def save(self, user_creator_features: UserCreatorFeatures) -> None:
        values = user_creator_features_values(user_creator_features)
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                UserCreatorFeaturesRecord,
                (
                    UserCreatorFeaturesRecord.user_id == user_creator_features.user_id,
                    UserCreatorFeaturesRecord.creator_id == user_creator_features.creator_id,
                ),
                values,
            )


class SqlAlchemyUserFeaturesRepository(BaseSqlAlchemyRepository, UserFeaturesRepository):
    def get_user_features(self, user_id: UUID) -> UserFeatures | None:
        with self.session_provider.session() as session:
            record = session.get(UserFeaturesRecord, user_id)
            return user_features_from_record(record) if record is not None else None

    def save(self, user_features: UserFeatures) -> None:
        values = user_features_values(user_features)
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                UserFeaturesRecord,
                (UserFeaturesRecord.user_id == user_features.user_id,),
                values,
            )


class SqlAlchemyCollabRepository(BaseSqlAlchemyRepository, CollabRepository):
    def get(self, collab_id: UUID) -> Collab | None:
        with self.session_provider.session() as session:
            record = session.get(CollabRecord, collab_id)
            return collab_from_record(record) if record is not None else None

    def save(self, collab: Collab) -> None:
        values = collab_values(collab)
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                CollabRecord,
                (CollabRecord.collab_id == collab.collab_id,),
                values,
            )

    def delete(self, collab_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(delete(CollabRecord).where(CollabRecord.collab_id == collab_id))

    def find_post_id_by_collab_id(self, collab_id: UUID) -> UUID | None:
        with self.session_provider.session() as session:
            return session.scalar(
                select(PostFeaturesRecord.post_id)
                .where(PostFeaturesRecord.collab_id == collab_id)
                .limit(1)
            )


class SqlAlchemyCommentPostRepository(BaseSqlAlchemyRepository, CommentPostRepository):
    def save_comment_post(self, comment_id: UUID, post_id: UUID) -> None:
        values = {"comment_id": comment_id, "post_id": post_id}
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                CommentPostRecord,
                (CommentPostRecord.comment_id == comment_id,),
                values,
            )

    def find_post_id_by_comment_id(self, comment_id: UUID) -> UUID | None:
        with self.session_provider.session() as session:
            return session.scalar(
                select(CommentPostRecord.post_id)
                .where(CommentPostRecord.comment_id == comment_id)
                .limit(1)
            )

    def delete_comment_post(self, comment_id: UUID) -> None:
        with self.session_provider.session() as session:
            session.execute(
                delete(CommentPostRecord).where(CommentPostRecord.comment_id == comment_id)
            )


class SqlAlchemyUserPostInteractionsRepository(
    BaseSqlAlchemyRepository,
    UserPostInteractionsRepository,
):
    def get(self, post_id: UUID, user_id: UUID) -> UserPostInteractions | None:
        with self.session_provider.session() as session:
            record = session.get(UserPostInteractionsRecord, (post_id, user_id))
            return user_post_interactions_from_record(record) if record is not None else None

    def save(self, interactions: UserPostInteractions) -> None:
        values = user_post_interactions_values(interactions)
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                UserPostInteractionsRecord,
                (
                    UserPostInteractionsRecord.post_id == interactions.post_id,
                    UserPostInteractionsRecord.user_id == interactions.user_id,
                ),
                values,
            )


class SqlAlchemyUserPostCommentInteractionRepository(
    BaseSqlAlchemyRepository,
    UserPostCommentInteractionRepository,
):
    def get(self, comment_id: UUID, user_id: UUID) -> UserPostCommentInteraction | None:
        with self.session_provider.session() as session:
            record = session.get(UserPostCommentInteractionRecord, (comment_id, user_id))
            return (
                user_post_comment_interaction_from_record(record)
                if record is not None
                else None
            )

    def save(self, interaction: UserPostCommentInteraction) -> None:
        values = user_post_comment_interaction_values(interaction)
        with self.session_provider.session() as session:
            self._update_or_insert(
                session,
                UserPostCommentInteractionRecord,
                (
                    UserPostCommentInteractionRecord.comment_id == interaction.comment_id,
                    UserPostCommentInteractionRecord.user_id == interaction.user_id,
                ),
                values,
            )
