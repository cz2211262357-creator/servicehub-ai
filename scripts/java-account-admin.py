"""Offline initial administrator provisioning and explicit password recovery; no default password."""

from __future__ import annotations

import argparse
import getpass
import sys
from uuid import uuid4

from email_validator import validate_email
from java_maintenance import (
    configuration,
    database,
    execute,
    insert,
    maintenance_lock,
    report_main,
    require,
    require_empty,
    require_java_schema,
    rows,
)
from pwdlib import PasswordHash


def new_password(from_stdin):
    if from_stdin:
        value = sys.stdin.readline(131).rstrip("\r\n")
    else:
        value = getpass.getpass("New password (12-128 characters): ")
        require(value == getpass.getpass("Confirm new password: "), "password_confirmation_failed")
    require(12 <= len(value) <= 128 and "\0" not in value, "invalid_password")
    return value


async def operate(args):
    _, options, _ = configuration(args.target_env, True)
    # Password hashing is deliberately outside all database locks.
    encoded = PasswordHash.recommended().hash(new_password(args.password_stdin))
    if args.operation == "bootstrap":
        require(
            1 <= len(args.tenant_name.strip()) <= 120 and 1 <= len(args.display_name.strip()) <= 80,
            "invalid_profile",
        )
        try:
            email = validate_email(args.email, check_deliverability=False).normalized
        except Exception:
            require(False, "invalid_email")
    async with database(options) as connection, maintenance_lock(connection):
        await connection.commit()
        await execute(connection, "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")
        await connection.begin()
        try:
            if args.operation == "bootstrap":
                await require_empty(connection)
                tenant = await insert(connection, "tenants", {"name": args.tenant_name.strip()})
                user = await insert(
                    connection,
                    "users",
                    {
                        "tenant_id": tenant,
                        "email": email,
                        "display_name": args.display_name.strip(),
                        "role": "admin",
                        "password_hash": encoded,
                        "is_active": True,
                        "session_version": 1,
                    },
                )
                action = "maintenance.admin_initialized"
            else:
                await require_java_schema(connection)
                member = await rows(
                    connection,
                    "SELECT id FROM users WHERE tenant_id=%s AND id=%s FOR UPDATE",
                    (args.tenant_id, args.user_id),
                )
                require(member, "member_not_found")
                tenant, user = args.tenant_id, args.user_id
                await execute(
                    connection,
                    "UPDATE users SET password_hash=%s,is_active=1,session_version=session_version+1 WHERE tenant_id=%s AND id=%s",
                    (encoded, tenant, user),
                )
                action = "maintenance.password_reset"
            await insert(
                connection,
                "audit_logs",
                {
                    "tenant_id": tenant,
                    "actor_id": None,
                    "action": action,
                    "target_type": "user",
                    "target_id": str(user),
                    "outcome": "success",
                    "request_id": "maintenance-" + uuid4().hex,
                    "details": {"mechanism": "offline_operator", "credentials_imported": False},
                },
            )
            await connection.commit()
        except Exception:
            await connection.rollback()
            raise
    return {"operation": args.operation, "tenant_id": tenant, "user_id": user}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    bootstrap = commands.add_parser("bootstrap")
    bootstrap.add_argument("--tenant-name", required=True)
    bootstrap.add_argument("--email", required=True)
    bootstrap.add_argument("--display-name", required=True)
    reset = commands.add_parser("reset-password")
    reset.add_argument("--tenant-id", type=int, required=True)
    reset.add_argument("--user-id", type=int, required=True)
    for command in (bootstrap, reset):
        command.add_argument("--target-env", required=True)
        command.add_argument("--password-stdin", action="store_true")
    args = parser.parse_args()
    return report_main(lambda: operate(args))


if __name__ == "__main__":
    raise SystemExit(main())
