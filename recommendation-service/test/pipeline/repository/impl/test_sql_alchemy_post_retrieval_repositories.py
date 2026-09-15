from datetime import datetime, timezone
from uuid import uuid4

import pytest

from block.entity.block_entity import BlockRecord
from pipeline.entity.post.post_features_entity import PostFeaturesRecord
from pipeline.entity.post.post_interaction_features_entity import PostInteractionFeaturesRecord
from pipeline.entity.post.post_tag_features_entity import PostTagFeaturesRecord
from pipeline.entity.user.user_creator_features_entity import UserCreatorFeaturesRecord
from pipeline.entity.user.user_features_entity import UserFeaturesRecord
from pipeline.repository.impl.sql_alchemy_colaborative_post_retrieval_repository import (
    SqlAlchemyCollaborativePostRetrievalRepository,
)
from pipeline.repository.impl.sql_alchemy_explorative_post_retrieval_repository import (
    SqlAlchemyExplorativePostRetrievalRepository,
)
from pipeline.repository.impl.sql_alchemy_semantic_post_retrieval_repository import (
    SqlAlchemySemanticPostRetrievalRepository,
)
from post.entity.user_post_interactions_entity import UserPostInteractionsRecord


pytestmark = pytest.mark.integration
T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


from follow.entity.follow_entity import FollowRecord

def seed_data(session_provider, user_id, creator_1_id, creator_2_id, creator_3_id, post_1_id, post_2_id, post_3_id, post_4_id, similar_user_id):
    with session_provider.session() as session:
        # User semantic profile
        session.add(UserFeaturesRecord(
            user_id=user_id,
            semantic_embedding=[0.1] * 1024,
            last_updated_at=T0,
            has_semantic_signal=True,
        ))

        # Similar User profile
        session.add(UserFeaturesRecord(
            user_id=similar_user_id,
            semantic_embedding=[0.15] * 1024, # similar
            last_updated_at=T0,
            has_semantic_signal=True,
        ))

        # Post 1 (by creator 1)
        session.add(PostFeaturesRecord(
            post_id=post_1_id,
            creator_id=creator_1_id,
            tags=["python", "ai"],
            tagged_users_ids=[],
            semantic_embedding=[0.12] * 1024, # High similarity
            created_at=T0,
        ))
        session.add(PostInteractionFeaturesRecord(
            post_id=post_1_id,
            decayed_engagement_score=100.0,
            last_updated_at=T0,
        ))

        # Post 2 (by creator 2, blocked)
        session.add(PostFeaturesRecord(
            post_id=post_2_id,
            creator_id=creator_2_id,
            tags=["rust"],
            tagged_users_ids=[],
            semantic_embedding=[0.12] * 1024,
            created_at=T0,
        ))
        session.add(PostInteractionFeaturesRecord(
            post_id=post_2_id,
            decayed_engagement_score=200.0, # Most popular, but will be filtered by block
            last_updated_at=T0,
        ))

        # Post 3 (by creator 1, already seen)
        session.add(PostFeaturesRecord(
            post_id=post_3_id,
            creator_id=creator_1_id,
            tags=["javascript"],
            tagged_users_ids=[],
            semantic_embedding=[0.12] * 1024,
            created_at=T0,
        ))
        session.add(PostInteractionFeaturesRecord(
            post_id=post_3_id,
            decayed_engagement_score=50.0,
            last_updated_at=T0,
        ))

        # Post 4 (by creator 3, followed by user)
        session.add(PostFeaturesRecord(
            post_id=post_4_id,
            creator_id=creator_3_id,
            tags=["ruby"],
            tagged_users_ids=[],
            semantic_embedding=[0.12] * 1024,
            created_at=T0,
        ))
        session.add(PostInteractionFeaturesRecord(
            post_id=post_4_id,
            decayed_engagement_score=150.0,
            last_updated_at=T0,
        ))
        
        # Block relation
        session.add(BlockRecord(
            blocker_id=user_id,
            blocked_id=creator_2_id,
            created_at=T0,
        ))

        # Follow relation
        session.add(FollowRecord(
            follower_id=user_id,
            followed_id=creator_3_id,
            created_at=T0,
        ))

        # Seen interaction
        session.add(UserPostInteractionsRecord(
            post_id=post_3_id,
            user_id=user_id,
            ever_seen=True,
        ))

        # User Creator Affinity
        session.add(UserCreatorFeaturesRecord(
            user_id=similar_user_id,
            creator_id=creator_1_id,
            affinity_score=10.0,
            last_updated_at=T0,
        ))

        # Post Tag Interactions
        session.add(PostTagFeaturesRecord(
            user_id=user_id,
            tag_name="ai", # user has seen "ai" tag
            last_updated_at=T0,
        ))

        session.commit()


def test_explorative_queries_exclude_blocked_and_seen(db_session_factory):
    # Arrange
    from shared.config.database import SQLAlchemySessionProvider
    session_provider = SQLAlchemySessionProvider(db_session_factory)
    
    user_id = uuid4()
    creator_1_id = uuid4()
    creator_2_id = uuid4()
    creator_3_id = uuid4()
    post_1_id = uuid4()
    post_2_id = uuid4()
    post_3_id = uuid4()
    post_4_id = uuid4()
    similar_user_id = uuid4()

    seed_data(session_provider, user_id, creator_1_id, creator_2_id, creator_3_id, post_1_id, post_2_id, post_3_id, post_4_id, similar_user_id)

    repo = SqlAlchemyExplorativePostRetrievalRepository(session_provider)

    # Act
    popular = repo.get_popular_posts(user_id, 10)
    unseen_tags = repo.get_unseen_tags_posts(user_id, 10)
    random_posts = repo.get_random_posts(user_id, 10)

    # Assert
    # Post 2 is blocked, Post 3 is seen, Post 4 is followed -> only Post 1 should be retrieved
    assert len(popular) == 1
    assert popular[0][0] == post_1_id
    assert popular[0][1] == 100.0

    # Unseen tags post will exclude Post 1 because it has "ai" tag, which user has seen
    assert len(unseen_tags) == 0

    # Random posts should only retrieve Post 1
    assert len(random_posts) == 1
    assert random_posts[0][0] == post_1_id


def test_collaborative_queries_compute_affinity_and_exclude_invalid(db_session_factory):
    # Arrange
    from shared.config.database import SQLAlchemySessionProvider
    session_provider = SQLAlchemySessionProvider(db_session_factory)
    
    user_id = uuid4()
    creator_1_id = uuid4()
    creator_2_id = uuid4()
    creator_3_id = uuid4()
    post_1_id = uuid4()
    post_2_id = uuid4()
    post_3_id = uuid4()
    post_4_id = uuid4()
    similar_user_id = uuid4()

    seed_data(session_provider, user_id, creator_1_id, creator_2_id, creator_3_id, post_1_id, post_2_id, post_3_id, post_4_id, similar_user_id)

    repo = SqlAlchemyCollaborativePostRetrievalRepository(session_provider)

    # Act
    similar_users = repo.get_similar_users(user_id, 10)
    assert len(similar_users) == 1
    assert similar_users[0][0] == similar_user_id
    
    posts = repo.get_posts_ordered_by_user_affinity(similar_users, user_id, 10, 10)

    # Assert
    # Post 2 blocked, Post 3 seen -> only Post 1 retrieved (collaborative doesn't filter followed creators)
    assert len(posts) == 1
    assert posts[0][0] == post_1_id


def test_semantic_queries_compute_distance_and_exclude_invalid(db_session_factory):
    # Arrange
    from shared.config.database import SQLAlchemySessionProvider
    session_provider = SQLAlchemySessionProvider(db_session_factory)
    
    user_id = uuid4()
    creator_1_id = uuid4()
    creator_2_id = uuid4()
    creator_3_id = uuid4()
    post_1_id = uuid4()
    post_2_id = uuid4()
    post_3_id = uuid4()
    post_4_id = uuid4()
    similar_user_id = uuid4()

    seed_data(session_provider, user_id, creator_1_id, creator_2_id, creator_3_id, post_1_id, post_2_id, post_3_id, post_4_id, similar_user_id)

    repo = SqlAlchemySemanticPostRetrievalRepository(session_provider)

    # Act
    posts = repo.get_similar_posts(user_id, 10)

    # Assert
    # Post 2 blocked, Post 3 seen. 
    # Post 1 and Post 4 have valid embeddings. (Both have [0.12]*1024, semantic query defaults to ASC distance).
    assert len(posts) == 2
    assert set(p[0] for p in posts) == {post_1_id, post_4_id}
