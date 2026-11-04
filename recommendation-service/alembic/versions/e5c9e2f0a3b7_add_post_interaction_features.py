"""add post interaction features

Creates the table in its final shape (raw_/decayed_ interaction stats).

Revision ID: e5c9e2f0a3b7
Revises: c4b8d1f9e2a6
Create Date: 2026-09-15 10:53:00.000000

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'e5c9e2f0a3b7'
down_revision = 'c4b8d1f9e2a6'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        'post_interaction_features',
        sa.Column('post_id', sa.Uuid(), nullable=False),
        sa.Column('raw_impressions', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('raw_views', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('raw_likes', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('raw_comments', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('raw_comments_likes', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('raw_shares', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('raw_fast_skips', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('raw_collab_requests', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('raw_collab_requests_accepted', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('raw_watch_time_average_percent', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('raw_watch_time', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_impressions', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_views_engagement', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_likes', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_comments', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_comments_likes', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_shares', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_fast_skips', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_collab_requests', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_collab_requests_accepted', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_watch_time_average_percent', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_watch_time', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('affinity_score', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('decayed_engagement_score', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('last_updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('post_id')
    )


def downgrade() -> None:
    op.drop_table('post_interaction_features')
