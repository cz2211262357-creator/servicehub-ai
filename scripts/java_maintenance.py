"""Private, offline maintenance primitives. Never print credentials or exception messages."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import unquote, urlsplit

import asyncmy
from asyncmy.cursors import DictCursor


class MaintenanceError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def require(condition, code="invalid_transfer"):
    if not condition:
        raise MaintenanceError(code)


def literal_environment(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        require("=" in line, "invalid_environment")
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        require(re.fullmatch(r"[A-Z][A-Z0-9_]*", key), "invalid_environment")
        require(key not in values, "duplicate_environment_key")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        values[key] = value
    return values


def workspace(values=None) -> Path:
    values = values or {}
    raw = values.get("SERVICEHUB_WORKSPACE_ROOT") or os.environ.get("SERVICEHUB_WORKSPACE_ROOT")
    require(bool(raw), "workspace_required")
    candidate = Path(raw)
    require(candidate.is_absolute() and candidate.is_dir(), "invalid_workspace")
    root = candidate.resolve()
    current = os.environ.get("SERVICEHUB_WORKSPACE_ROOT")
    if current:
        require(root == Path(current).resolve(), "workspace_conflict")
    return root


def inside(path: Path, root: Path, *, exists=False) -> Path:
    require(path.is_absolute(), "absolute_path_required")
    require(not path.is_symlink(), "symlink_refused")
    resolved = path.resolve()
    require(resolved.is_relative_to(root.resolve()) and resolved != root.resolve(), "path_escape")
    if exists:
        require(resolved.is_file(), "file_missing")
    return resolved


def configuration(path: str, target: bool):
    env_path = Path(path).resolve()
    values = literal_environment(env_path)
    root = workspace(values)
    inside(env_path, root, exists=True)
    if target:
        url = values.get("SERVICEHUB_JAVA_JDBC_URL", "jdbc:mysql://127.0.0.1:3307/servicehub_java")
        require(url.startswith("jdbc:mysql://"), "invalid_database_configuration")
        parsed = urlsplit(url[5:])
        user = values.get("SERVICEHUB_JAVA_DB_USER", "servicehub_java")
        password = values.get("SERVICEHUB_JAVA_DB_PASSWORD", "")
        upload = Path(values.get("SERVICEHUB_JAVA_UPLOAD_DIR", str(root / ".data/java/uploads")))
        upload = inside(upload, root / ".data/java")
        require(upload.is_relative_to(root), "path_escape")
    else:
        url = values.get("SERVICEHUB_DATABASE_URL") or values.get("DATABASE_URL", "")
        require(url.startswith("mysql+asyncmy://"), "invalid_database_configuration")
        parsed = urlsplit(url)
        user, password = unquote(parsed.username or ""), unquote(parsed.password or "")
        source_root = Path(values.get("SERVICEHUB_PROJECT_ROOT", str(root))).resolve()
        require(source_root.is_relative_to(root), "path_escape")
        upload = Path(
            values.get("SERVICEHUB_UPLOAD_DIR") or values.get("UPLOAD_DIR", ".data/uploads")
        )
        if not upload.is_absolute():
            upload = source_root / upload
        upload = inside(upload, root)
    database = parsed.path.lstrip("/")
    require(
        parsed.hostname and re.fullmatch(r"[A-Za-z0-9_]+", database) and user and password,
        "invalid_database_configuration",
    )
    require(not target or parsed.username is None, "invalid_database_configuration")
    return (
        root,
        {
            "host": parsed.hostname,
            "port": parsed.port or 3306,
            "user": user,
            "password": password,
            "db": database,
            "charset": "utf8mb4",
            "autocommit": False,
            "connect_timeout": 5,
            "cursor_cls": DictCursor,
        },
        upload,
    )


@asynccontextmanager
async def database(options):
    connection = await asyncmy.connect(**options)
    try:
        async with connection.cursor() as cursor:
            await cursor.execute("SET time_zone='+00:00'")
        yield connection
    finally:
        connection.close()


async def rows(connection, sql, parameters=()):
    async with connection.cursor() as cursor:
        await cursor.execute(sql, parameters)
        return list(await cursor.fetchall())


async def execute(connection, sql, parameters=()):
    async with connection.cursor() as cursor:
        await cursor.execute(sql, parameters)
        return cursor.lastrowid


async def fingerprint(connection):
    row = (await rows(connection, "SELECT @@server_uuid AS server_id,DATABASE() AS database_name"))[
        0
    ]
    return hashlib.sha256((row["server_id"] + "|" + row["database_name"]).encode()).hexdigest()


@asynccontextmanager
async def maintenance_lock(connection):
    identity = await fingerprint(connection)
    name = "servicehub-maintenance:" + identity[:32]
    acquired = (await rows(connection, "SELECT GET_LOCK(%s,5) AS acquired", (name,)))[0]
    require(acquired["acquired"] == 1, "maintenance_busy")
    try:
        yield identity
    finally:
        try:
            await execute(connection, "SELECT RELEASE_LOCK(%s)", (name,))
        except Exception:
            # Closing the connection releases its advisory lock; preserve the primary outcome.
            pass


async def require_java_schema(connection):
    schema = await rows(
        connection, "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"
    )
    names = {row["TABLE_NAME"] if "TABLE_NAME" in row else row["table_name"] for row in schema}
    require(
        {"flyway_schema_history", "conversation_topics", "knowledge_index_generations"} <= names,
        "java_schema_required",
    )
    history = await rows(
        connection,
        "SELECT version,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
    )
    require(
        history and all(row["success"] for row in history) and history[-1]["version"] == "6",
        "java_schema_required",
    )
    return names


async def require_empty(connection):
    names = await require_java_schema(connection)
    for name in sorted(names - {"flyway_schema_history"}):
        require(re.fullmatch(r"[A-Za-z0-9_]+", name), "invalid_schema")
        # Full-range locking at SERIALIZABLE prevents a competing initializer inserting tenants.
        existing = await rows(
            connection, "SELECT 1 AS present FROM " + quote(name) + " LIMIT 1 FOR UPDATE"
        )
        require(not existing, "target_not_empty")


def quote(name):
    require(re.fullmatch(r"[A-Za-z0-9_]+", name), "invalid_identifier")
    return chr(96) + name + chr(96)


async def insert(connection, table, row):
    fields = list(row)
    sql = (
        "INSERT INTO "
        + quote(table)
        + "("
        + ",".join(map(quote, fields))
        + ") VALUES("
        + ",".join(["%s"] * len(fields))
        + ")"
    )
    values = []
    for name in fields:
        value = row[name]
        if isinstance(value, (dict, list)):
            value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        values.append(value)
    return await execute(connection, sql, values)


def utc_now():
    return datetime.now(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def utc_value(value):
    require(
        isinstance(value, str)
        and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z", value),
        "invalid_utc",
    )
    return datetime.fromisoformat(value.replace("Z", "+00:00")).replace(tzinfo=None)


def report_main(operation):
    try:
        result = asyncio.run(operation())
        print(json.dumps({"status": "ok", **result}, ensure_ascii=False))
        return 0
    except MaintenanceError as error:
        print(json.dumps({"status": "error", "code": error.code}))
        return 2
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception as error:
        # Driver exceptions may contain a DSN, passwords, row values or SQL parameters.
        diagnostic = {
            "status": "error",
            "code": "maintenance_failed",
            "exception_type": type(error).__name__,
        }
        if error.args and type(error.args[0]) is int:
            diagnostic["driver_code"] = error.args[0]
        print(json.dumps(diagnostic))
        return 2
