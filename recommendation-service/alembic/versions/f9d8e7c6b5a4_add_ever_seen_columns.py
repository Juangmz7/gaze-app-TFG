"""add ever seen columns

No-op: user_post_interactions.ever_seen is created by a7f3e8c2d1b4.
Kept so the revision chain stays intact.

Revision ID: f9d8e7c6b5a4
Revises: e5c9e2f0a3b7
Create Date: 2026-09-15 12:00:00.000000

"""
from typing import Sequence, Union



# revision identifiers, used by Alembic.
revision: str = 'f9d8e7c6b5a4'
down_revision: Union[str, None] = 'e5c9e2f0a3b7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Folded into a7f3e8c2d1b4 (user_post_interactions): created there in its final shape.
    pass


def downgrade() -> None:
    pass
