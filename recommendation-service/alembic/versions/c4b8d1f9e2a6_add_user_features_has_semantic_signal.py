"""Add semantic signal flag to user features.

Revision ID: c4b8d1f9e2a6
Revises: a7f3e8c2d1b4
Create Date: 2026-09-13

"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "c4b8d1f9e2a6"
down_revision: Union[str, None] = "a7f3e8c2d1b4"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    columns = {column["name"] for column in inspector.get_columns("user_features")}
    if "has_semantic_signal" not in columns:
        op.add_column(
            "user_features",
            sa.Column(
                "has_semantic_signal",
                sa.Boolean(),
                server_default="false",
                nullable=False,
            ),
        )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    columns = {column["name"] for column in inspector.get_columns("user_features")}
    if "has_semantic_signal" in columns:
        op.drop_column("user_features", "has_semantic_signal")
