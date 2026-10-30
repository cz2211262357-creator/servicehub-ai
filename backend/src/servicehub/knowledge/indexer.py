import asyncio

from redis.exceptions import RedisError
from sqlalchemy import delete, select

from servicehub.ai.embedding import HTTPEmbeddingProvider
from servicehub.core.errors import BusinessError
from servicehub.jobs.handlers import PermanentJobError, RetryableJobError
from servicehub.jobs.service import JobService
from servicehub.knowledge.hybrid_index import HybridIndex
from servicehub.knowledge.models import (
    DocumentVersion,
    KnowledgeActiveVersion,
    KnowledgeChunk,
    KnowledgeDocument,
)
from servicehub.knowledge.parser import DocumentParseError, parse_document, split_pages
from servicehub.knowledge.retrieval import VectorIndex
from servicehub.knowledge.storage import PrivateStorage


class KnowledgeIndexer:
    def __init__(self, sessions, redis, settings, embedding=None):
        self.sessions, self.redis, self.settings = sessions, redis, settings
        self.embedding = embedding
        self.jobs = JobService(sessions)

    async def mark_failed(self, lease, version_id, code):
        async with self.sessions() as session, session.begin():
            await self.jobs.lock_domain(session, lease.job_id, lease.tenant_id)
            if await self.jobs.lock_valid(session, lease):
                version = await session.get(DocumentVersion, version_id)
                version.status, version.error_code = "failed", code

    async def build_version(self, lease, document_version_id):
        async with self.sessions() as session:
            version = await session.scalar(
                select(DocumentVersion).where(
                    DocumentVersion.id == document_version_id,
                    DocumentVersion.tenant_id == lease.tenant_id,
                )
            )
            if not version:
                raise PermanentJobError("version_not_found")
            path = PrivateStorage(self.settings).resolve(version.storage_key)
        try:
            pages = await asyncio.to_thread(parse_document, path, version.media_type)
        except (DocumentParseError, OSError) as exc:
            code = exc.code if isinstance(exc, DocumentParseError) else "source_file_unavailable"
            await self.mark_failed(lease, version.id, code)
            raise PermanentJobError(code) from None

        hybrid = getattr(self.settings, "retrieval_backend", "mysql_legacy") == "redis_hybrid"
        parsed_chunks = list(split_pages(pages))

        # Stage inactive chunks in a short transaction. No provider network calls hold MySQL locks.
        async with self.sessions() as session, session.begin():
            document = await session.scalar(
                select(KnowledgeDocument)
                .where(
                    KnowledgeDocument.id == version.document_id,
                    KnowledgeDocument.tenant_id == lease.tenant_id,
                )
                .with_for_update()
            )
            if not await self.jobs.lock_valid(session, lease):
                return {"discarded": True}
            if document.deleted:
                await self.jobs.finish(lease, {"deleted": True}, session=session)
                return {"deleted": True}
            chunks = []
            if hybrid:
                chunks = list(
                    await session.scalars(
                        select(KnowledgeChunk)
                        .where(
                            KnowledgeChunk.tenant_id == lease.tenant_id,
                            KnowledgeChunk.version_id == version.id,
                        )
                        .order_by(KnowledgeChunk.ordinal)
                    )
                )
                # Versions are immutable. Reuse IDs after a partial index failure so
                # late/expired attempts cannot leave same-version orphan candidates.
                if chunks and [(chunk.ordinal, chunk.page, chunk.text) for chunk in chunks] != [
                    (i, page, text) for i, (page, text) in enumerate(parsed_chunks)
                ]:
                    raise PermanentJobError("staged_content_changed")
            else:
                await session.execute(
                    delete(KnowledgeChunk).where(
                        KnowledgeChunk.tenant_id == lease.tenant_id,
                        KnowledgeChunk.version_id == version.id,
                    )
                )
            if not chunks:
                chunks = [
                    KnowledgeChunk(
                        tenant_id=lease.tenant_id,
                        document_id=document.id,
                        version_id=version.id,
                        ordinal=i,
                        page=page,
                        text=text,
                    )
                    for i, (page, text) in enumerate(parsed_chunks)
                ]
                session.add_all(chunks)
            current = await session.get(DocumentVersion, version.id)
            current.status, current.error_code = "processing", None
            await session.flush()
            staged = [(chunk.id, chunk.text) for chunk in chunks]

        online = self.settings.model_mode == "online"
        if hybrid or online:
            try:
                index = (HybridIndex if hybrid else VectorIndex)(
                    self.redis, self.settings.embedding_model, self.settings.embedding_dimensions
                )
                # Confirm schema before attempting to activate even an empty document.
                await index.ensure()
                provider = self.embedding or HTTPEmbeddingProvider(self.settings)
                for start in range(0, len(staged), 10):
                    batch = staged[start : start + 10]
                    vectors = (
                        await provider.embed([text for _, text in batch])
                        if online
                        else [None] * len(batch)
                    )
                    for (chunk_id, text), vector in zip(batch, vectors, strict=True):
                        if hybrid:
                            await index.write(
                                chunk_id, lease.tenant_id, text, vector, version_id=version.id
                            )
                        else:
                            await index.write(
                                chunk_id, lease.tenant_id, vector, version_id=version.id
                            )
            except (BusinessError, RedisError, ValueError, TimeoutError) as exc:
                code = exc.code if isinstance(exc, BusinessError) else "index_storage_unavailable"
                await self.mark_failed(lease, version.id, code)
                raise RetryableJobError(code) from None

        async with self.sessions() as session, session.begin():
            document = await session.scalar(
                select(KnowledgeDocument)
                .where(
                    KnowledgeDocument.id == version.document_id,
                    KnowledgeDocument.tenant_id == lease.tenant_id,
                )
                .with_for_update()
            )
            if not await self.jobs.lock_valid(session, lease):
                return {"discarded": True}
            if document.deleted:
                await self.jobs.finish(lease, {"deleted": True}, session=session)
                return {"deleted": True}
            current = await session.get(DocumentVersion, version.id)
            current.status, current.error_code = "ready", None
            if self.settings.model_mode == "online":
                current.embedding_model = self.settings.embedding_model
                current.embedding_dimensions = self.settings.embedding_dimensions
            active = await session.scalar(
                select(KnowledgeActiveVersion)
                .where(
                    KnowledgeActiveVersion.tenant_id == lease.tenant_id,
                    KnowledgeActiveVersion.document_id == document.id,
                )
                .with_for_update()
            )
            active_version = (
                await session.get(DocumentVersion, active.version_id) if active else None
            )
            if not active_version or active_version.number <= current.number:
                if active:
                    active.version_id = current.id
                else:
                    session.add(
                        KnowledgeActiveVersion(
                            tenant_id=lease.tenant_id,
                            document_id=document.id,
                            version_id=current.id,
                        )
                    )
                document.version += 1
            if not await self.jobs.finish(
                lease, {"chunks": len(staged), "version_id": current.id}, session=session
            ):
                await session.rollback()
                return {"discarded": True}
        return {"chunks": len(staged)}
