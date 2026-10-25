"""Private, test-only JSON-lines bridge to the unchanged Python API on an empty owned database."""

import base64
import json
import sys
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import Request
from fastapi.testclient import TestClient
from servicehub.auth.models import Tenant, User
from servicehub.conversations.models import Conversation
from servicehub.core.config import Settings
from servicehub.db.metadata import metadata
from servicehub.main import create_app
from sqlalchemy import text


def wire(value):
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, datetime):
        return (
            value.replace(tzinfo=value.tzinfo or UTC)
            .astimezone(UTC)
            .isoformat()
            .replace("+00:00", "Z")
        )
    raise TypeError(type(value).__name__)


def main():
    if len(sys.argv) != 2:
        raise ValueError("One owned fixture configuration path is required")
    config_path = Path(sys.argv[1]).resolve()
    config = json.loads(config_path.read_text(encoding="utf-8"))
    workspace = Path(config["workspace"]).resolve()
    if not config_path.is_relative_to(workspace / ".cache" / "java-verification"):
        raise ValueError("Fixture configuration must stay in the project verification directory")
    settings = Settings(_env_file=None, **config["settings"])
    target = urlsplit(settings.database_url)
    if (
        settings.environment != "test"
        or target.hostname not in {"127.0.0.1", "localhost"}
        or target.path != "/servicehub_java_it"
        or target.username != "servicehub_test"
    ):
        raise ValueError("Only the isolated reference database may be used")
    if not settings.project_root.is_relative_to(workspace / ".cache" / "java-verification"):
        raise ValueError("Reference uploads must remain inside this fixture")
    app = create_app(settings)
    owner = config["owner"]

    @app.post("/__fixture__/control", include_in_schema=False)
    async def control(request: Request):
        if request.headers.get("x-fixture-key") != owner:
            raise ValueError("Wrong fixture owner")
        command = await request.json()
        if command["op"] == "init":
            async with app.state.engine.begin() as connection:
                count = await connection.scalar(
                    text(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
                    )
                )
                if count:
                    raise ValueError("Reference schema must be empty")
                await connection.run_sync(metadata.create_all)
            async with app.state.sessions() as session, session.begin():
                session.add_all(
                    [Tenant(id=1001, name="Synthetic A"), Tenant(id=1002, name="Synthetic B")]
                )
                await session.flush()
                for uid in (2001, 2002, 2003, 2004, 5001):
                    role = (
                        "admin" if uid == 2001 else "customer" if uid in (2003, 5001) else "agent"
                    )
                    session.add(
                        User(
                            id=uid,
                            tenant_id=1002 if uid == 5001 else 1001,
                            email=f"user{uid}@example.com",
                            display_name=f"Synthetic {uid}",
                            password_hash=command["password_hash"],
                            role=role,
                        )
                    )
                await session.flush()
                session.add(
                    Conversation(id=3001, tenant_id=1001, customer_id=2003, title="original")
                )
            return {"initialized": True}
        if command["op"] != "sql":
            raise ValueError("Unsupported fixture operation")
        results = []
        async with app.state.sessions() as session, session.begin():
            for statement in command["statements"]:
                sql = statement["sql"].strip()
                if (
                    sql.split(None, 1)[0].upper() not in {"SELECT", "INSERT", "UPDATE", "DELETE"}
                    or ";" in sql
                ):
                    raise ValueError("Only bounded fixture DML is allowed")
                result = await session.execute(text(sql), statement.get("params", {}))
                results.append(
                    {
                        "rows": [dict(row) for row in result.mappings()]
                        if result.returns_rows
                        else [],
                        "lastrowid": getattr(result, "lastrowid", None),
                        "rowcount": result.rowcount,
                    }
                )
        return json.loads(json.dumps({"results": results}, default=wire))

    with TestClient(app, raise_server_exceptions=False) as client:
        for line in sys.stdin:
            command = json.loads(line)
            try:
                if command["kind"] == "close":
                    print(json.dumps({"id": command["id"], "closed": True}), flush=True)
                    break
                client.cookies.clear()
                if command["kind"] == "control":
                    response = client.post(
                        "/__fixture__/control",
                        json=command["command"],
                        headers={"x-fixture-key": owner},
                    )
                elif command["kind"] == "request":
                    if not command["path"].startswith("/api/v1/"):
                        raise ValueError("Only API contracts may be requested")
                    options = {"headers": command.get("headers", {})}
                    if command.get("parts") is not None:
                        options["data"] = command.get("fields", {})
                        options["files"] = [
                            (
                                part["name"],
                                (
                                    part["filename"],
                                    base64.b64decode(part["content"]),
                                    part["media_type"],
                                ),
                            )
                            for part in command["parts"]
                        ]
                    elif "raw_body" in command:
                        options["content"] = command["raw_body"].encode("utf-8")
                    elif "body" in command:
                        options["json"] = command["body"]
                    response = client.request(command["method"], command["path"], **options)
                else:
                    raise ValueError("Unsupported bridge command")
                print(
                    json.dumps(
                        {
                            "id": command["id"],
                            "status": response.status_code,
                            "headers": list(response.headers.multi_items()),
                            "body_base64": base64.b64encode(response.content).decode("ascii"),
                        },
                        default=wire,
                    ),
                    flush=True,
                )
            except Exception as error:
                print(
                    json.dumps({"id": command.get("id"), "bridge_error": type(error).__name__}),
                    flush=True,
                )
                raise


if __name__ == "__main__":
    main()
