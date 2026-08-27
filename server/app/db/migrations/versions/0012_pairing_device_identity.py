"""Add stable normalized installation identity to paired devices.

Revision ID: 0012
Revises: 0010
Create Date: 2026-08-24

The label intentionally skips 0011. Alembic down_revision, not filename order,
defines the chain; the later duplicate-groups migration must chain from head.
"""

from __future__ import annotations

import re
from collections import defaultdict

import sqlalchemy as sa
from alembic import op

revision = "0012"
down_revision = "0010"
branch_labels = None
depends_on = None


def _normalized(value: str) -> str:
    return value.strip().casefold()


_TOKEN_HASH = re.compile(r"[0-9a-fA-F]{64}\Z")


def _has_valid_credential(row: sa.RowMapping) -> bool:
    return not bool(row["revoked"]) and bool(_TOKEN_HASH.fullmatch(str(row["token_hash"] or "")))


def upgrade() -> None:
    with op.batch_alter_table("paired_device") as batch_op:
        batch_op.add_column(sa.Column("installation_id", sa.String(128), nullable=True))

    connection = op.get_bind()
    rows = (
        connection.execute(
            sa.text(
                "SELECT device_id, name, paired_at, token_hash, revoked, last_seen_at "
                "FROM paired_device"
            )
        )
        .mappings()
        .all()
    )
    groups: dict[str, list[sa.RowMapping]] = defaultdict(list)
    for row in rows:
        normalized = _normalized(row["device_id"])
        if not normalized:
            normalized = row["device_id"]
        groups[normalized].append(row)

    for installation_id, candidates in groups.items():
        winner = max(
            candidates,
            key=lambda row: (
                _has_valid_credential(row),
                str(row["paired_at"] or ""),
                str(row["last_seen_at"] or ""),
            ),
        )
        for row in candidates:
            if row["device_id"] != winner["device_id"]:
                connection.execute(
                    sa.text("DELETE FROM paired_device WHERE device_id = :device_id"),
                    {"device_id": row["device_id"]},
                )
        if winner["device_id"] != installation_id:
            connection.execute(
                sa.text(
                    "UPDATE paired_device SET device_id = :installation_id "
                    "WHERE device_id = :device_id"
                ),
                {"installation_id": installation_id, "device_id": winner["device_id"]},
            )
        connection.execute(
            sa.text(
                "UPDATE paired_device SET installation_id = :installation_id "
                "WHERE device_id = :installation_id"
            ),
            {"installation_id": installation_id},
        )

    with op.batch_alter_table("paired_device") as batch_op:
        batch_op.alter_column("installation_id", existing_type=sa.String(128), nullable=False)
        batch_op.create_unique_constraint("uq_paired_device_installation_id", ["installation_id"])


def downgrade() -> None:
    with op.batch_alter_table("paired_device") as batch_op:
        batch_op.drop_constraint("uq_paired_device_installation_id", type_="unique")
        batch_op.drop_column("installation_id")
