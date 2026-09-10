"""Initial schema.

Creates the full PostgreSQL schema for the recommendation service, including:
  - pgvector extension
  - all application tables (derived from the existing SQLAlchemy entities)
  - all primary keys, unique constraints
  - indexes declared inside SQLAlchemy entities
  - HNSW vector index on post_features.semantic_embedding (vector_ip_ops)

Revision ID: a7f3e8c2d1b4
Revises: -
Create Date: 2026-09-08

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from pgvector.sqlalchemy import Vector

# revision identifiers, used by Alembic.
revision: str = "a7f3e8c2d1b4"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# ---------------------------------------------------------------------------
# Upgrade
# ---------------------------------------------------------------------------


def upgrade() -> None:
    # ------------------------------------------------------------------
    # 1. PostgreSQL extension: pgvector
    # ------------------------------------------------------------------
    op.execute("CREATE EXTENSION IF NOT EXISTS vector;")

    # ------------------------------------------------------------------
    # 2. Tables (no FK dependencies first, then dependent ones)
    # ------------------------------------------------------------------

    # processed_events
    # Source: shared/entity/processed_event_entity.py - ProcessedEventRecord
    op.create_table(
        "processed_events",
        sa.Column("event_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("correlation_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("event_name", sa.String(255), nullable=False),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("event_id", "correlation_id", name="pk_processed_events"),
    )

    # blocks
    # Source: block/entity/block_entity.py - BlockRecord
    op.create_table(
        "blocks",
        sa.Column("blocker_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("blocked_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("blocker_id", "blocked_id", name="pk_blocks"),
    )

    # follows
    # Source: follow/entity/follow_entity.py - FollowRecord
    op.create_table(
        "follows",
        sa.Column("follower_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("followed_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("follower_id", "followed_id", name="pk_follows"),
    )

    # collabs
    # Source: post/entity/collab_entity.py - CollabRecord
    op.create_table(
        "collabs",
        sa.Column("collab_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("title", sa.String(512), nullable=False),
        sa.Column("created_by", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("collab_id", name="pk_collabs"),
    )

    # comment_posts
    # Source: post/entity/comment_post_entity.py - CommentPostRecord
    op.create_table(
        "comment_posts",
        sa.Column("comment_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("post_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.PrimaryKeyConstraint("comment_id", name="pk_comment_posts"),
    )

    # user_post_interactions
    # Source: post/entity/user_post_interactions_entity.py - UserPostInteractionsRecord
    op.create_table(
        "user_post_interactions",
        sa.Column("post_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("user_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("ever_liked", sa.Boolean(), nullable=False),
        sa.Column("ever_unliked", sa.Boolean(), nullable=False),
        sa.Column("ever_unshared", sa.Boolean(), nullable=False),
        sa.Column("ever_request_collab_deleted", sa.Boolean(), nullable=False),
        sa.Column("ever_shared", sa.Boolean(), nullable=False),
        sa.Column("ever_requested_collab", sa.Boolean(), nullable=False),
        sa.Column("comment_count", sa.Integer(), nullable=False),
        sa.PrimaryKeyConstraint("post_id", "user_id", name="pk_user_post_interactions"),
    )

    # user_post_comment_interactions
    # Source: post/entity/user_post_comment_interaction_entity.py -
    #         UserPostCommentInteractionRecord
    op.create_table(
        "user_post_comment_interactions",
        sa.Column("comment_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("user_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("ever_liked", sa.Boolean(), nullable=False),
        sa.Column("ever_unliked", sa.Boolean(), nullable=False),
        sa.PrimaryKeyConstraint(
            "comment_id", "user_id", name="pk_user_post_comment_interactions"
        ),
    )

    # post_features
    # Source: pipeline/entity/post/post_features_entity.py - PostFeaturesRecord
    # Note: semantic_embedding is Vector(1024) (pgvector), not JSON.
    op.create_table(
        "post_features",
        sa.Column("post_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("creator_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("collab_id", sa.Uuid(as_uuid=True), nullable=True),
        sa.Column("collab_title", sa.String(512), nullable=True),
        sa.Column("description", sa.String(), nullable=True),
        sa.Column("tags", sa.JSON(), nullable=False),
        sa.Column("tagged_users_ids", sa.JSON(), nullable=False),
        sa.Column("semantic_embedding", Vector(1024), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("post_id", name="pk_post_features"),
    )

    # post_tag_features
    # Source: pipeline/entity/post/post_tag_features_entity.py - PostTagFeaturesRecord
    #         + pipeline/entity/interaction/interaction_stats_columns.py
    op.create_table(
        "post_tag_features",
        sa.Column("user_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("tag_name", sa.String(128), nullable=False),
        # raw interaction stats
        sa.Column("raw_impressions", sa.Integer(), nullable=False),
        sa.Column("raw_views", sa.Integer(), nullable=False),
        sa.Column("raw_likes", sa.Integer(), nullable=False),
        sa.Column("raw_comments", sa.Integer(), nullable=False),
        sa.Column("raw_comments_likes", sa.Integer(), nullable=False),
        sa.Column("raw_shares", sa.Integer(), nullable=False),
        sa.Column("raw_fast_skips", sa.Integer(), nullable=False),
        sa.Column("raw_collab_requests", sa.Integer(), nullable=False),
        sa.Column("raw_collab_requests_accepted", sa.Integer(), nullable=False),
        sa.Column("raw_watch_time_average_percent", sa.Float(), nullable=False),
        sa.Column("raw_watch_time", sa.Float(), nullable=False),
        # decayed interaction stats
        sa.Column("decayed_impressions", sa.Float(), nullable=False),
        sa.Column("decayed_views_engagement", sa.Float(), nullable=False),
        sa.Column("decayed_likes", sa.Float(), nullable=False),
        sa.Column("decayed_comments", sa.Float(), nullable=False),
        sa.Column("decayed_comments_likes", sa.Float(), nullable=False),
        sa.Column("decayed_shares", sa.Float(), nullable=False),
        sa.Column("decayed_fast_skips", sa.Float(), nullable=False),
        sa.Column("decayed_collab_requests", sa.Float(), nullable=False),
        sa.Column("decayed_collab_requests_accepted", sa.Float(), nullable=False),
        sa.Column("decayed_watch_time_average_percent", sa.Float(), nullable=False),
        sa.Column("decayed_watch_time", sa.Float(), nullable=False),
        sa.Column("affinity_score", sa.Float(), nullable=False),
        sa.Column("last_updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("user_id", "tag_name", name="pk_post_tag_features"),
    )

    # user_features
    # Source: pipeline/entity/user/user_features_entity.py - UserFeaturesRecord
    # Note: semantic_embedding is Vector(1024) (pgvector), not JSON.
    op.create_table(
        "user_features",
        sa.Column("user_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("semantic_embedding", Vector(1024), nullable=False),
        sa.Column("last_updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("user_id", name="pk_user_features"),
    )

    # user_creator_features
    # Source: pipeline/entity/user/user_creator_features_entity.py -
    #         UserCreatorFeaturesRecord
    #         + pipeline/entity/interaction/interaction_stats_columns.py
    op.create_table(
        "user_creator_features",
        sa.Column("user_id", sa.Uuid(as_uuid=True), nullable=False),
        sa.Column("creator_id", sa.Uuid(as_uuid=True), nullable=False),
        # raw interaction stats
        sa.Column("raw_impressions", sa.Integer(), nullable=False),
        sa.Column("raw_views", sa.Integer(), nullable=False),
        sa.Column("raw_likes", sa.Integer(), nullable=False),
        sa.Column("raw_comments", sa.Integer(), nullable=False),
        sa.Column("raw_comments_likes", sa.Integer(), nullable=False),
        sa.Column("raw_shares", sa.Integer(), nullable=False),
        sa.Column("raw_fast_skips", sa.Integer(), nullable=False),
        sa.Column("raw_collab_requests", sa.Integer(), nullable=False),
        sa.Column("raw_collab_requests_accepted", sa.Integer(), nullable=False),
        sa.Column("raw_watch_time_average_percent", sa.Float(), nullable=False),
        sa.Column("raw_watch_time", sa.Float(), nullable=False),
        # decayed interaction stats
        sa.Column("decayed_impressions", sa.Float(), nullable=False),
        sa.Column("decayed_views_engagement", sa.Float(), nullable=False),
        sa.Column("decayed_likes", sa.Float(), nullable=False),
        sa.Column("decayed_comments", sa.Float(), nullable=False),
        sa.Column("decayed_comments_likes", sa.Float(), nullable=False),
        sa.Column("decayed_shares", sa.Float(), nullable=False),
        sa.Column("decayed_fast_skips", sa.Float(), nullable=False),
        sa.Column("decayed_collab_requests", sa.Float(), nullable=False),
        sa.Column("decayed_collab_requests_accepted", sa.Float(), nullable=False),
        sa.Column("decayed_watch_time_average_percent", sa.Float(), nullable=False),
        sa.Column("decayed_watch_time", sa.Float(), nullable=False),
        sa.Column("affinity_score", sa.Float(), nullable=False),
        sa.Column("last_updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("user_id", "creator_id", name="pk_user_creator_features"),
    )

    # ------------------------------------------------------------------
    # 3. Indexes declared inside SQLAlchemy entities (kept in entities)
    # ------------------------------------------------------------------

    # ix_post_features_collab_id — declared in PostFeaturesRecord.__table_args__
    op.create_index(
        "ix_post_features_collab_id",
        "post_features",
        ["collab_id"],
        unique=False,
    )

    # ------------------------------------------------------------------
    # 4. HNSW vector ANN index on post_features.semantic_embedding
    #
    # Design rationale:
    #   - Embeddings are L2-normalised (normalize_embeddings=True in the
    #     SemanticEmbeddingModelService).
    #   - For unit-length vectors, inner-product similarity is equivalent to
    #     cosine similarity.
    #   - vector_ip_ops (inner product) therefore provides the most efficient
    #     ANN retrieval path for this workload.
    #   - HNSW is preferred over IVFFlat because it does not require a
    #     separate training/index-build step and delivers better recall.
    #
    # This index is managed here (not in the entity) because Alembic does not
    # natively support HNSW index creation through op.create_index().
    # ------------------------------------------------------------------
    op.execute(
        """
        CREATE INDEX ix_post_features_semantic_embedding_hnsw
        ON post_features
        USING hnsw (semantic_embedding vector_ip_ops);
        """
    )


# ---------------------------------------------------------------------------
# Downgrade
# ---------------------------------------------------------------------------


def downgrade() -> None:
    # ------------------------------------------------------------------
    # Reverse in safe dependency order:
    # HNSW index -> entity-declared indexes -> tables -> extension
    # ------------------------------------------------------------------

    # 4. HNSW / vector index
    op.execute("DROP INDEX IF EXISTS ix_post_features_semantic_embedding_hnsw;")

    # 3. Entity-declared indexes
    op.drop_index("ix_post_features_collab_id", table_name="post_features")

    # 2. Tables
    op.drop_table("user_creator_features")
    op.drop_table("user_features")
    op.drop_table("post_tag_features")
    op.drop_table("post_features")
    op.drop_table("user_post_comment_interactions")
    op.drop_table("user_post_interactions")
    op.drop_table("comment_posts")
    op.drop_table("collabs")
    op.drop_table("follows")
    op.drop_table("blocks")
    op.drop_table("processed_events")

    # 1. Extension
    # NOTE: dropping the vector extension is safe here because all vector
    # columns have already been removed with their tables above.
    op.execute("DROP EXTENSION IF EXISTS vector;")
