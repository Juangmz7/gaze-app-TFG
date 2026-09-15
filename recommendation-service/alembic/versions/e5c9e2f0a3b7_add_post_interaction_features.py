"""add post interaction features

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
        sa.Column('impressions', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('views', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('likes', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('comments', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('shares', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('fast_skips', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('collab_requests', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('collab_requests_accepted', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('watch_time_average_percent', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('watch_time', sa.Float(), nullable=False, server_default='0.0'),
        sa.Column('last_updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('decayed_engagement_score', sa.Float(), nullable=False, server_default='0.0'),
        sa.PrimaryKeyConstraint('post_id')
    )


def downgrade() -> None:
    op.drop_table('post_interaction_features')
