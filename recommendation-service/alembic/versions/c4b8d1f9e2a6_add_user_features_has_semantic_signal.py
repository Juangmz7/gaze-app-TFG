"""Add semantic signal flag to user features.

No-op: user_features.has_semantic_signal is created by a7f3e8c2d1b4.
Kept so the revision chain stays intact.

Revision ID: c4b8d1f9e2a6
Revises: a7f3e8c2d1b4
Create Date: 2026-09-13

"""

from typing import Sequence, Union


# revision identifiers, used by Alembic.
revision: str = "c4b8d1f9e2a6"
down_revision: Union[str, None] = "a7f3e8c2d1b4"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Folded into a7f3e8c2d1b4 (user_features): created there in its final shape.
    pass


def downgrade() -> None:
    pass
