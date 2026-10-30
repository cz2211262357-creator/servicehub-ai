"""Idempotently import the synthetic policy corpus into a separate local merchant.

Run python -m scripts.seed_quality. Never updates old order facts or passwords.
Only development settings are accepted; these are synthetic fixtures, not ERP data.
"""

import argparse
import asyncio
import io
import json
import os
from pathlib import Path

from servicehub.auth.models import User
from servicehub.auth.schemas import Principal
from servicehub.core.config import Settings
from servicehub.db.engine import create_database
from servicehub.knowledge.models import DocumentVersion, KnowledgeDocument
from servicehub.knowledge.service import DocumentService
from sqlalchemy import select
from starlette.datastructures import UploadFile

from scripts.seed import POLICY_TITLE, seed_development


async def seed(args):
    source = Path(__file__).resolve().parents[1]
    settings = Settings(_env_file=args.env_file, project_root=args.state_root or source)
    if settings.environment != "development":
        raise RuntimeError("Synthetic corpus import requires development environment")
    data = source / "evals" / "servicehub"
    manifest = json.loads((data / "manifest.json").read_text(encoding="utf-8"))
    engine, sessions = create_database(settings)
    try:
        seeded = await seed_development(
            sessions,
            settings,
            os.environ.get("SERVICEHUB_SEED_PASSWORD", "ServiceHub-2026!"),
            tenant_names=("星桥商城（合成业务资料）",),
        )
        tenant = seeded["tenants"][0]
        async with sessions() as session:
            admin = await session.scalar(
                select(User).where(
                    User.tenant_id == tenant["id"],
                    User.email == "admin@example.com",
                    User.role == "admin",
                    User.is_active.is_(True),
                )
            )
            if admin is None:
                raise RuntimeError(
                    "Current local merchant admin is unavailable; no permissions changed"
                )
            principal = Principal(admin.id, admin.tenant_id, admin.role, admin.session_version)
            generic = await session.scalar(
                select(KnowledgeDocument).where(
                    KnowledgeDocument.tenant_id == tenant["id"],
                    KnowledgeDocument.title == POLICY_TITLE,
                    KnowledgeDocument.deleted.is_(False),
                    KnowledgeDocument.enabled.is_(True),
                )
            )
        service = DocumentService(sessions, settings)
        if generic:
            await service.set_enabled(principal, generic.id, False, generic.version, "quality-seed")
        documents = []
        for item in manifest["documents"]:
            title = item["policy_id"] + " · " + item["title"]
            async with sessions() as session:
                existing = (
                    await session.execute(
                        select(KnowledgeDocument, DocumentVersion)
                        .join(
                            DocumentVersion,
                            (DocumentVersion.document_id == KnowledgeDocument.id)
                            & (DocumentVersion.tenant_id == KnowledgeDocument.tenant_id),
                        )
                        .where(
                            KnowledgeDocument.tenant_id == tenant["id"],
                            KnowledgeDocument.title == title,
                            KnowledgeDocument.deleted.is_(False),
                            KnowledgeDocument.enabled.is_(True),
                            DocumentVersion.sha256 == item["sha256"],
                        )
                        .order_by(DocumentVersion.number.desc())
                        .limit(1)
                    )
                ).first()
            if existing:
                document_id, version_id = existing[0].id, existing[1].id
            else:
                document = await service.upload(
                    principal,
                    UploadFile(
                        io.BytesIO((data / item["path"]).read_bytes()),
                        filename=item["policy_id"] + ".md",
                    ),
                    title=title,
                    request_id="quality-corpus-seed",
                )
                document_id, version_id = document.id, document.latest_version.id
            documents.append(
                {
                    "policy_id": item["policy_id"],
                    "document_id": document_id,
                    "version_id": version_id,
                }
            )
        result = {
            "synthetic": True,
            "tenant": tenant,
            "corpus_snapshot": manifest["snapshot_sha256"],
            "documents": documents,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(
            json.dumps(
                {
                    "tenant_id": tenant["id"],
                    "policy_documents": len(documents),
                    "orders": tenant["orders"],
                    "indexing": "queued via actual worker",
                    "manifest": str(args.output),
                },
                ensure_ascii=False,
            )
        )
    finally:
        await engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--state-root", type=Path)
    parser.add_argument("--output", type=Path, default=Path(".data/quality-seed.json"))
    asyncio.run(seed(parser.parse_args()))


if __name__ == "__main__":
    main()
