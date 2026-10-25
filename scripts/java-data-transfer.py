"""Offline legacy-business handover into an empty Java schema; no task/session resurrection."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

from java_maintenance import (
    MaintenanceError,
    configuration,
    database,
    execute,
    fingerprint,
    insert,
    inside,
    maintenance_lock,
    quote,
    report_main,
    require,
    require_empty,
    rows,
    utc_now,
    utc_value,
    workspace,
)

FIELDS = {
    "tenants": "id name created_at",
    "users": "id tenant_id email display_name role is_active created_at",
    "conversations": (
        "id tenant_id customer_id assigned_agent_id title status version generation_epoch "
        "last_event_seq updated_at claimed_at closed_at created_at"
    ),
    "orders": (
        "id tenant_id customer_id order_number status total_amount currency tracking_number "
        "carrier delivery_summary created_at"
    ),
    "order_items": "id tenant_id order_id product_name sku quantity unit_price created_at",
    "ticket_drafts": "id tenant_id customer_id order_id kind reason version expires_at consumed created_at",
    "after_sale_tickets": (
        "id tenant_id draft_id customer_id order_id kind reason status version "
        "idempotency_key request_hash created_at"
    ),
    "ticket_events": "id tenant_id ticket_id actor_id from_status to_status note created_at",
    "knowledge_documents": "id tenant_id title uploaded_by enabled deleted version created_at",
    "document_versions": (
        "id tenant_id document_id number storage_key filename media_type size_bytes "
        "sha256 status error_code embedding_model embedding_dimensions created_at"
    ),
    "messages": (
        "id tenant_id conversation_id sender_id author_role client_message_id body citations "
        "draft_id created_at"
    ),
    "audit_logs": "id tenant_id actor_id action target_type target_id outcome request_id details created_at",
}
FIELDS = {table: tuple(names.split()) for table, names in FIELDS.items()}
NULLABLE = {
    "assigned_agent_id",
    "claimed_at",
    "closed_at",
    "tracking_number",
    "carrier",
    "delivery_summary",
    "from_status",
    "error_code",
    "embedding_model",
    "embedding_dimensions",
    "sender_id",
    "client_message_id",
    "draft_id",
    "actor_id",
}
BOOLEAN = {"is_active", "consumed", "enabled", "deleted"}
AMOUNTS = {"total_amount", "unit_price"}
JSON_FIELDS = {"citations", "details"}
COUNTERS = {
    "version",
    "generation_epoch",
    "last_event_seq",
    "quantity",
    "number",
    "size_bytes",
    "embedding_dimensions",
}
MAXIMUM = {
    "name": 120,
    "email": 254,
    "display_name": 80,
    "role": 20,
    "title": 200,
    "status": 24,
    "order_number": 40,
    "currency": 3,
    "tracking_number": 80,
    "carrier": 80,
    "delivery_summary": 255,
    "product_name": 200,
    "sku": 80,
    "kind": 24,
    "reason": 65535,
    "idempotency_key": 80,
    "request_hash": 64,
    "from_status": 24,
    "to_status": 24,
    "note": 500,
    "storage_key": 100,
    "filename": 255,
    "media_type": 80,
    "sha256": 64,
    "error_code": 64,
    "embedding_model": 160,
    "author_role": 20,
    "client_message_id": 80,
    "body": 65535,
    "action": 80,
    "target_type": 40,
    "target_id": 100,
    "outcome": 24,
    "request_id": 96,
}
KEY = re.compile(r"[a-f0-9]{32}\.(txt|md|pdf)")
HASH = re.compile(r"[a-f0-9]{64}")
FORMAT = "servicehub-python-business-v1"
MAX_UPLOAD = 10 * 1024 * 1024


def encode_cell(name, value):
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.replace(tzinfo=UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")
    if isinstance(value, Decimal):
        return format(value, ".2f")
    if name in JSON_FIELDS and isinstance(value, (str, bytes)):
        return json.loads(value)
    if name in BOOLEAN:
        return bool(value)
    return value


def parse_manifest(path: str):
    root = workspace()
    transfer = root / ".data/java/transfer"
    manifest_path = inside(Path(path), transfer, exists=True)
    require(manifest_path.stat().st_size <= 100 * 1024 * 1024, "manifest_too_large")

    def no_duplicate_keys(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate_json_key")
            result[key] = value
        return result

    document = json.loads(
        manifest_path.read_text(encoding="utf-8"), object_pairs_hook=no_duplicate_keys
    )
    validate_document(document, manifest_path.parent)
    return root, manifest_path, document


def validate_document(document, bundle):
    require(
        isinstance(document, dict)
        and set(document) == {"format", "source_fingerprint", "exported_at", "tables", "files"},
        "invalid_manifest",
    )
    require(
        document["format"] == FORMAT
        and isinstance(document["source_fingerprint"], str)
        and HASH.fullmatch(document["source_fingerprint"]),
        "invalid_manifest",
    )
    utc_value(document["exported_at"])
    tables = document["tables"]
    require(isinstance(tables, dict) and set(tables) == set(FIELDS), "unknown_table")
    indexed = {}
    for table, columns in FIELDS.items():
        values = tables[table]
        require(isinstance(values, list) and len(values) <= 200000, "invalid_rows")
        indexed[table] = {}
        for row in values:
            require(isinstance(row, dict) and set(row) == set(columns), "invalid_columns")
            for name, value in row.items():
                if value is None:
                    require(name in NULLABLE, "null_required_field")
                elif (
                    name == "id"
                    or (name.endswith("_id") and name not in MAXIMUM)
                    or name == "uploaded_by"
                ):
                    require(type(value) is int and 0 < value < 2**63, "invalid_identifier")
                elif name.endswith("_at"):
                    utc_value(value)
                elif name in BOOLEAN:
                    require(type(value) is bool, "invalid_boolean")
                elif name in AMOUNTS:
                    require(
                        isinstance(value, str)
                        and re.fullmatch(r"(?:0|[1-9]\d{0,9})\.\d{2}", value),
                        "invalid_amount",
                    )
                elif name in COUNTERS:
                    require(type(value) is int and 0 <= value < 2**63, "invalid_counter")
                    if name in {
                        "version",
                        "number",
                        "quantity",
                        "size_bytes",
                        "embedding_dimensions",
                    }:
                        require(value > 0, "invalid_counter")
                elif name in JSON_FIELDS:
                    require(
                        isinstance(value, list if name == "citations" else dict), "invalid_json"
                    )
                    require(
                        len(json.dumps(value, ensure_ascii=False).encode()) <= 65535,
                        "json_too_large",
                    )
                else:
                    require(
                        isinstance(value, str)
                        and len(value) <= MAXIMUM[name]
                        and "\0" not in value,
                        "invalid_string",
                    )
            if table == "conversations":
                require(
                    len(row["title"]) <= 120
                    and row["status"] in {"AI_ACTIVE", "WAITING_HUMAN", "HUMAN_ACTIVE", "CLOSED"},
                    "invalid_conversation",
                )
            if table == "users":
                require(row["role"] in {"admin", "agent", "customer"}, "invalid_role")
            require(row["id"] not in indexed[table], "duplicate_id")
            indexed[table][row["id"]] = row
    require(indexed["tenants"], "empty_source")

    def reference(table, row, field, target, optional=False):
        identity = row[field]
        if identity is None and optional:
            return None
        found = indexed[target].get(identity)
        require(
            found is not None and (target == "tenants" or found["tenant_id"] == row["tenant_id"]),
            "invalid_relationship",
        )
        return found

    for table, values in tables.items():
        for row in values:
            if table != "tenants":
                require(row["tenant_id"] in indexed["tenants"], "invalid_tenant")
            if table == "conversations":
                customer = reference(table, row, "customer_id", "users")
                require(customer["role"] == "customer", "invalid_relationship")
                reference(table, row, "assigned_agent_id", "users", True)
            elif table == "orders":
                customer = reference(table, row, "customer_id", "users")
                require(
                    customer["role"] == "customer" and re.fullmatch(r"[A-Z]{3}", row["currency"]),
                    "invalid_relationship",
                )
            elif table == "order_items":
                reference(table, row, "order_id", "orders")
            elif table in {"ticket_drafts", "after_sale_tickets"}:
                reference(table, row, "customer_id", "users")
                order = reference(table, row, "order_id", "orders")
                require(order["customer_id"] == row["customer_id"], "invalid_relationship")
                if table == "after_sale_tickets":
                    draft = reference(table, row, "draft_id", "ticket_drafts")
                    require(
                        draft["customer_id"] == row["customer_id"]
                        and draft["order_id"] == row["order_id"]
                        and draft["consumed"],
                        "invalid_relationship",
                    )
                    require(HASH.fullmatch(row["request_hash"]), "invalid_hash")
            elif table == "ticket_events":
                reference(table, row, "ticket_id", "after_sale_tickets")
                reference(table, row, "actor_id", "users")
            elif table == "knowledge_documents":
                reference(table, row, "uploaded_by", "users")
            elif table == "document_versions":
                reference(table, row, "document_id", "knowledge_documents")
                require(
                    KEY.fullmatch(row["storage_key"])
                    and HASH.fullmatch(row["sha256"])
                    and 0 < row["size_bytes"] <= MAX_UPLOAD,
                    "invalid_file",
                )
                require(
                    row["filename"]
                    and not any(char in row["filename"] for char in "/\\:")
                    and all(ord(char) >= 32 for char in row["filename"]),
                    "invalid_filename",
                )
                expected_media = {
                    "txt": "text/plain",
                    "md": "text/markdown",
                    "pdf": "application/pdf",
                }
                require(
                    row["media_type"] == expected_media[row["storage_key"].rsplit(".", 1)[1]],
                    "invalid_media",
                )
            elif table == "messages":
                conversation = reference(table, row, "conversation_id", "conversations")
                sender = reference(table, row, "sender_id", "users", True)
                require(
                    row["author_role"] in {"customer", "agent", "assistant", "ai", "system"},
                    "invalid_role",
                )
                if row["author_role"] == "customer":
                    require(
                        sender and sender["id"] == conversation["customer_id"],
                        "invalid_relationship",
                    )
                if row["draft_id"] is not None:
                    draft = reference(table, row, "draft_id", "ticket_drafts")
                    require(
                        draft["customer_id"] == conversation["customer_id"], "invalid_relationship"
                    )
                for citation in row["citations"]:
                    require(isinstance(citation, dict), "invalid_citation")
                    for field, target in (
                        ("document_id", "knowledge_documents"),
                        ("version_id", "document_versions"),
                    ):
                        if field in citation:
                            referenced = indexed[target].get(citation[field])
                            require(
                                referenced and referenced["tenant_id"] == row["tenant_id"],
                                "invalid_citation",
                            )
            elif table == "audit_logs":
                reference(table, row, "actor_id", "users", True)
    require(isinstance(document["files"], list), "invalid_files")
    expected = {}
    for version in tables["document_versions"]:
        key = version["storage_key"]
        identity = (version["sha256"], version["size_bytes"])
        require(key not in expected or expected[key] == identity, "conflicting_file")
        expected[key] = identity
    seen = set()
    for file in document["files"]:
        require(
            isinstance(file, dict) and set(file) == {"storage_key", "path", "sha256", "size_bytes"},
            "invalid_files",
        )
        key = file["storage_key"]
        require(
            isinstance(key, str)
            and KEY.fullmatch(key)
            and key not in seen
            and file["path"] == "files/" + key,
            "invalid_file_path",
        )
        require(
            key in expected and expected[key] == (file["sha256"], file["size_bytes"]),
            "invalid_file",
        )
        path = inside(bundle / file["path"], bundle, exists=True)
        require(
            path.stat().st_size == file["size_bytes"] and digest(path) == file["sha256"],
            "file_hash_mismatch",
        )
        seen.add(key)
    require(seen == set(expected), "file_missing")
    return indexed


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(262144), b""):
            sha.update(chunk)
    return sha.hexdigest()


def owned_cleanup(path, base):
    if path is None or not path.exists():
        return
    resolved = inside(path, base)
    require(
        resolved.name.startswith((".export-", ".import-")) and not path.is_symlink(),
        "cleanup_scope_refused",
    )
    shutil.rmtree(resolved)


def database_row(row):
    return {
        name: utc_value(value) if name.endswith("_at") and value is not None else value
        for name, value in row.items()
    }


async def export_data(args):
    root, options, upload = configuration(args.source_env, False)
    target = inside(Path(args.output), root / ".data/java/transfer")
    require(target.name.endswith(".json") and not target.parent.exists(), "output_exists")
    transfer = root / ".data/java/transfer"
    transfer.mkdir(parents=True, exist_ok=True)
    stage = transfer / (".export-" + uuid4().hex)
    stage.mkdir()
    try:
        (stage / "files").mkdir()
        async with database(options) as connection:
            await execute(connection, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            await execute(connection, "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
            source = await fingerprint(connection)
            tables = {}
            for table, columns in FIELDS.items():
                data = await rows(
                    connection,
                    "SELECT "
                    + ",".join(map(quote, columns))
                    + " FROM "
                    + quote(table)
                    + " ORDER BY id",
                )
                tables[table] = [
                    {name: encode_cell(name, row[name]) for name in columns} for row in data
                ]
            await connection.rollback()
        files = {}
        for version in tables["document_versions"]:
            key = version["storage_key"]
            require(KEY.fullmatch(key), "invalid_file_path")
            source_file = inside(upload / key, upload, exists=True)
            require(
                source_file.stat().st_size == version["size_bytes"] <= MAX_UPLOAD
                and digest(source_file) == version["sha256"],
                "file_hash_mismatch",
            )
            if key not in files:
                shutil.copyfile(source_file, stage / "files" / key)
                files[key] = {
                    "storage_key": key,
                    "path": "files/" + key,
                    "sha256": version["sha256"],
                    "size_bytes": version["size_bytes"],
                }
        manifest = {
            "format": FORMAT,
            "source_fingerprint": source,
            "exported_at": utc_now(),
            "tables": tables,
            "files": list(files.values()),
        }
        validate_document(manifest, stage)
        (stage / target.name).write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        target.parent.parent.mkdir(parents=True, exist_ok=True)
        stage.rename(target.parent)
        return {
            "operation": "export",
            "rows": {name: len(data) for name, data in tables.items()},
            "files": len(files),
        }
    finally:
        owned_cleanup(stage, transfer)


async def validate_data(args):
    _, _, document = parse_manifest(args.input)
    return {
        "operation": "validate",
        "rows": {name: len(data) for name, data in document["tables"].items()},
        "files": len(document["files"]),
    }


def save_journal(path, value):
    temporary = path.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


async def write_business(connection, document):
    tables = document["tables"]
    drafts = {row["id"]: row for row in tables["ticket_drafts"]}
    topic_ids = {}
    epochs = {}
    for table in FIELDS:
        values = tables[table]
        for original in values:
            row = database_row(original)
            if table == "users":
                row.update(
                    password_hash="!migrated:" + secrets.token_urlsafe(32),
                    is_active=False,
                    session_version=1,
                )
            elif table == "conversations":
                row["generation_epoch"] += 1
                row["version"] += 1
                row["last_event_seq"] = 0
                epochs[row["id"]] = row["generation_epoch"]
            elif table == "ticket_drafts" and not row["consumed"]:
                row["expires_at"] = min(row["expires_at"], datetime.now(UTC).replace(tzinfo=None))
                row["version"] += 1
            elif table == "after_sale_tickets":
                draft = drafts[row["draft_id"]]
                canonical = json.dumps(
                    [
                        draft["id"],
                        draft["version"],
                        draft["order_id"],
                        draft["kind"],
                        draft["reason"],
                    ],
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
                row["request_hash"] = hashlib.sha256(canonical.encode()).hexdigest()
            elif table == "document_versions":
                row.update(
                    status="failed",
                    error_code="migration_reindex_required",
                    embedding_model=None,
                    embedding_dimensions=None,
                    job_id=None,
                    index_execution_version=None,
                )
            elif table == "messages":
                # History remains visible; old prompts and unverified assistant results are not execution context.
                row.update(
                    ai_run_id=None,
                    trigger_run_id=None,
                    trigger_job_id=None,
                    context_run_id=None,
                    topic_id=topic_ids[row["conversation_id"]],
                    routing_state="RESOLVED",
                    context_status="EXCLUDED",
                    accepted_generation_epoch=epochs[row["conversation_id"]],
                )
            await insert(connection, table, row)
            if table == "conversations":
                topic = await insert(
                    connection,
                    "conversation_topics",
                    {
                        "tenant_id": row["tenant_id"],
                        "conversation_id": row["id"],
                        "title": "默认话题",
                        "facts": {},
                        "created_at": row["created_at"],
                        "last_activity_at": row["updated_at"],
                    },
                )
                topic_ids[row["id"]] = topic
                await execute(
                    connection,
                    "UPDATE conversations SET active_topic_id=%s WHERE id=%s AND tenant_id=%s",
                    (topic, row["id"], row["tenant_id"]),
                )


async def import_data(args):
    require(args.allow_empty_target, "empty_target_opt_in_required")
    root, manifest_path, document = parse_manifest(args.input)
    configured_root, options, upload = configuration(args.target_env, True)
    require(root == configured_root, "workspace_conflict")
    transfer = root / ".data/java/transfer"
    batch = uuid4().hex
    stage = transfer / (".import-" + batch)
    stage.mkdir()
    journal_path = transfer / ("import-" + batch + ".json")
    journal = {
        "batch": batch,
        "status": "preparing",
        "manifest_sha256": digest(manifest_path),
        "target_fingerprint": None,
        "files": document["files"],
        "started_at": utc_now(),
    }
    promoted = []
    committed = False
    preserve = False
    try:
        for file in document["files"]:
            destination = stage / file["storage_key"]
            shutil.copyfile(manifest_path.parent / file["path"], destination)
            require(
                destination.stat().st_size == file["size_bytes"]
                and digest(destination) == file["sha256"],
                "file_hash_mismatch",
            )
        async with database(options) as connection, maintenance_lock(connection) as target:
            require(target != document["source_fingerprint"], "same_source")
            journal["target_fingerprint"] = target
            save_journal(journal_path, journal)
            await connection.commit()
            await execute(connection, "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE")
            await connection.begin()
            try:
                await require_empty(connection)
                upload.mkdir(parents=True, exist_ok=True)
                require(not any(upload.iterdir()), "target_files_not_empty")
                journal["status"] = "promoting"
                save_journal(journal_path, journal)
                for file in document["files"]:
                    destination = inside(upload / file["storage_key"], upload)
                    # CREATE_NEW: never overwrite or remove another batch's file.
                    with destination.open("xb") as output:
                        promoted.append(destination)
                        with (stage / file["storage_key"]).open("rb") as source:
                            shutil.copyfileobj(source, output)
                        output.flush()
                        os.fsync(output.fileno())
                    require(digest(destination) == file["sha256"], "file_hash_mismatch")
                await write_business(connection, document)
                for tenant in document["tables"]["tenants"]:
                    await insert(
                        connection,
                        "audit_logs",
                        {
                            "tenant_id": tenant["id"],
                            "actor_id": None,
                            "action": "migration.imported",
                            "target_type": "migration",
                            "target_id": batch,
                            "outcome": "success",
                            "request_id": "transfer-" + batch,
                            "details": {
                                "files": len(document["files"]),
                                "credentials_restored": False,
                                "historical_jobs_restored": False,
                            },
                        },
                    )
                journal["status"] = "committing"
                save_journal(journal_path, journal)
                # Protect files before COMMIT can be sent, including task cancellation
                # and KeyboardInterrupt (BaseException, not Exception).
                preserve = True
                try:
                    await connection.commit()
                    committed = True
                except BaseException as error:
                    # A new connection can observe no marker while the original COMMIT is
                    # still in flight. Absence never proves rollback; preserve every file
                    # until the transaction is independently known to have ended.
                    preserve = True
                    journal["status"] = "commit_outcome_unknown"
                    save_journal(journal_path, journal)
                    if not isinstance(error, Exception):
                        raise
                    raise MaintenanceError("commit_outcome_unknown") from None
            except Exception:
                if not committed:
                    try:
                        await connection.rollback()
                    except Exception:
                        pass
                raise
        journal["status"] = "committed"
        journal["finished_at"] = utc_now()
        save_journal(journal_path, journal)
        return {
            "operation": "import",
            "batch": batch,
            "rows": {name: len(values) for name, values in document["tables"].items()},
            "files": len(promoted),
            "users_require_new_password": True,
            "knowledge_requires_reindex": True,
        }
    finally:
        if not committed and not preserve:
            for path in promoted:
                inside(path, upload, exists=True).unlink()
            journal["status"] = "rolled_back"
            save_journal(journal_path, journal)
        owned_cleanup(stage, transfer)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    export = commands.add_parser("export")
    export.add_argument("--source-env", required=True)
    export.add_argument("--output", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("--input", required=True)
    target = commands.add_parser("import")
    target.add_argument("--target-env", required=True)
    target.add_argument("--input", required=True)
    target.add_argument("--allow-empty-target", action="store_true")
    args = parser.parse_args()
    action = {"export": export_data, "validate": validate_data, "import": import_data}[
        args.operation
    ]
    return report_main(lambda: action(args))


if __name__ == "__main__":
    raise SystemExit(main())
