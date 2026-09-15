"""add ever seen columns

Revision ID: f9d8e7c6b5a4
Revises: e5c9e2f0a3b7
Create Date: 2026-09-15 12:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f9d8e7c6b5a4'
down_revision: Union[str, None] = 'e5c9e2f0a3b7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('user_post_interactions', sa.Column('ever_seen', sa.Boolean(), server_default='false', nullable=False))


def downgrade() -> None:
    op.drop_column('user_post_interactions', 'ever_seen')
