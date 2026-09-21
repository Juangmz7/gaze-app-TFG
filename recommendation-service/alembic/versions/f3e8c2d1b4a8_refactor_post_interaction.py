"""refactor post interaction features

Revision ID: f3e8c2d1b4a8
Revises: f9d8e7c6b5a4
Create Date: 2026-09-21 12:00:00.000000

"""
from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = 'f3e8c2d1b4a8'
down_revision = 'f9d8e7c6b5a4'
branch_labels = None
depends_on = None


def upgrade() -> None:
    # We drop the old raw columns without raw_ prefix
    op.drop_column('post_interaction_features', 'impressions')
    op.drop_column('post_interaction_features', 'views')
    op.drop_column('post_interaction_features', 'likes')
    op.drop_column('post_interaction_features', 'comments')
    op.drop_column('post_interaction_features', 'shares')
    op.drop_column('post_interaction_features', 'fast_skips')
    op.drop_column('post_interaction_features', 'collab_requests')
    op.drop_column('post_interaction_features', 'collab_requests_accepted')
    op.drop_column('post_interaction_features', 'watch_time_average_percent')
    op.drop_column('post_interaction_features', 'watch_time')

    # Add new raw columns
    op.add_column('post_interaction_features', sa.Column('raw_impressions', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('post_interaction_features', sa.Column('raw_views', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('post_interaction_features', sa.Column('raw_likes', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('post_interaction_features', sa.Column('raw_comments', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('post_interaction_features', sa.Column('raw_comments_likes', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('post_interaction_features', sa.Column('raw_shares', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('post_interaction_features', sa.Column('raw_fast_skips', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('post_interaction_features', sa.Column('raw_collab_requests', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('post_interaction_features', sa.Column('raw_collab_requests_accepted', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('post_interaction_features', sa.Column('raw_watch_time_average_percent', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('raw_watch_time', sa.Float(), nullable=False, server_default='0.0'))

    # Add decayed columns
    op.add_column('post_interaction_features', sa.Column('decayed_impressions', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_views_engagement', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_likes', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_comments', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_comments_likes', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_shares', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_fast_skips', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_collab_requests', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_collab_requests_accepted', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_watch_time_average_percent', sa.Float(), nullable=False, server_default='0.0'))
    op.add_column('post_interaction_features', sa.Column('decayed_watch_time', sa.Float(), nullable=False, server_default='0.0'))

    op.add_column('post_interaction_features', sa.Column('affinity_score', sa.Float(), nullable=False, server_default='0.0'))


def downgrade() -> None:
    pass
