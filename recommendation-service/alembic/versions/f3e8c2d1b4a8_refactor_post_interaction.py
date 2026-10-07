"""refactor post interaction features

No-op: post_interaction_features is created with its raw_/decayed_ columns
by e5c9e2f0a3b7.
Kept so the revision chain stays intact.

Revision ID: f3e8c2d1b4a8
Revises: f9d8e7c6b5a4
Create Date: 2026-09-21 12:00:00.000000

"""


# revision identifiers, used by Alembic.
revision = 'f3e8c2d1b4a8'
down_revision = 'f9d8e7c6b5a4'
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Folded into e5c9e2f0a3b7 (post_interaction_features): created there in its final shape.
    pass


def downgrade() -> None:
    pass
