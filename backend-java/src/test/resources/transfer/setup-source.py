"""Only initializes the test's disposable legacy database with synthetic rows."""
import asyncio
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path

from servicehub.db.metadata import metadata
from sqlalchemy.ext.asyncio import create_async_engine


async def main():
    config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    fixture = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    upload = Path(config["uploads"])
    upload.mkdir(parents=True, exist_ok=True)
    for key, content in fixture["files"].items():
        (upload / key).write_bytes(content.encode("utf-8"))
    for row in fixture["tables"]["document_versions"]:
        content = (upload / row["storage_key"]).read_bytes()
        row["size_bytes"] = len(content)
        row["sha256"] = hashlib.sha256(content).hexdigest()
    engine = create_async_engine(config["url"])
    async with engine.begin() as connection:
        await connection.run_sync(metadata.create_all)
        for name in ("tenants", "users", "conversations", "orders", "order_items",
                     "ticket_drafts", "after_sale_tickets", "ticket_events",
                     "knowledge_documents", "document_versions", "conversation_events",
                     "jobs", "ai_runs", "messages"):
            for row in fixture["tables"][name]:
                for key, value in list(row.items()):
                    if isinstance(value, str) and key.endswith("_at"):
                        row[key] = datetime.fromisoformat(value.replace("Z", "+00:00"))
                await connection.execute(metadata.tables[name].insert().values(**row))
    await engine.dispose()


asyncio.run(main())
