from uuid import UUID

from sqlalchemy import and_, delete, insert, or_, update

from follow.entity.follow_entity import FollowRecord
from follow.model.follow import Follow
from follow.repository.follow_repository import FollowRepository
from shared.config.database import SQLAlchemySessionProvider


class SqlAlchemyFollowRepository(FollowRepository):
    def __init__(self, session_provider: SQLAlchemySessionProvider):
        self.session_provider = session_provider

    def create_follow(self, follow: Follow) -> None:
        values = {
            "follower_id": follow.follower_id,
            "followed_id": follow.followed_id,
            "created_at": follow.created_at,
        }
        with self.session_provider.session() as session:
            result = session.execute(
                update(FollowRecord)
                .where(
                    FollowRecord.follower_id == follow.follower_id,
                    FollowRecord.followed_id == follow.followed_id,
                )
                .values(**values)
            )
            if result.rowcount == 0:
                session.execute(insert(FollowRecord).values(**values))

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
