import json

from sqlalchemy import func, select

from servicehub.conversations.models import Conversation, Message
from servicehub.core.errors import BusinessError
from servicehub.jobs.persistence import enqueue_job
from servicehub.knowledge.models import DocumentVersion, KnowledgeActiveVersion, KnowledgeDocument
from servicehub.knowledge.schemas import DocumentDTO, VersionDTO
from servicehub.knowledge.storage import PrivateStorage
from servicehub.operations.audit import AuditService


class DocumentService:
    def __init__(self, sessions, settings):
        self.sessions, self.settings = sessions, settings
        self.storage = PrivateStorage(settings)

    async def row(self, session, principal, document_id, lock=False):
        query = select(KnowledgeDocument).where(
            KnowledgeDocument.id == document_id,
            KnowledgeDocument.tenant_id == principal.tenant_id,
            KnowledgeDocument.deleted.is_(False),
        )
        row = await session.scalar(query.with_for_update() if lock else query)
        if not row:
            raise BusinessError("not_found", "文档不存在或无权访问", 404)
        return row

    async def dto(self, session, row):
        latest = await session.scalar(
            select(DocumentVersion)
            .where(
                DocumentVersion.tenant_id == row.tenant_id, DocumentVersion.document_id == row.id
            )
            .order_by(DocumentVersion.number.desc())
            .limit(1)
        )
        active = await session.scalar(
            select(KnowledgeActiveVersion.version_id).where(
                KnowledgeActiveVersion.tenant_id == row.tenant_id,
                KnowledgeActiveVersion.document_id == row.id,
            )
        )
        return DocumentDTO.model_validate(row).model_copy(
            update={
                "latest_version": VersionDTO.model_validate(latest) if latest else None,
                "active_version_id": active,
            }
        )

    async def upload(self, principal, upload, title=None, document_id=None, request_id="-"):
        stored = await self.storage.save(upload)
        try:
            async with self.sessions() as session, session.begin():
                if document_id:
                    row = await self.row(session, principal, document_id, lock=True)
                    number = 1 + (
                        await session.scalar(
                            select(func.max(DocumentVersion.number)).where(
                                DocumentVersion.tenant_id == principal.tenant_id,
                                DocumentVersion.document_id == row.id,
                            )
                        )
                        or 0
                    )
                    row.version += 1
                else:
                    row = KnowledgeDocument(
                        tenant_id=principal.tenant_id,
                        uploaded_by=principal.user_id,
                        title=(title or stored.filename).strip()[:200],
                    )
                    session.add(row)
                    await session.flush()
                    number = 1
                version = DocumentVersion(
                    tenant_id=principal.tenant_id,
                    document_id=row.id,
                    number=number,
                    storage_key=stored.key,
                    filename=stored.filename,
                    media_type=stored.media_type,
                    size_bytes=stored.size,
                    sha256=stored.sha256,
                )
                session.add(version)
                await session.flush()
                job = await enqueue_job(
                    session,
                    "knowledge_index",
                    f"version:{version.id}",
                    {"tenant_id": principal.tenant_id, "document_version_id": version.id},
                )
                version.job_id = job.id
                AuditService.record(
                    session,
                    principal,
                    "knowledge.uploaded",
                    "document",
                    row.id,
                    "success",
                    request_id,
                    {"version": number, "size": stored.size},
                )
                await session.flush()
                return await self.dto(session, row)
        except Exception:
            self.storage.resolve(stored.key).unlink(missing_ok=True)
            raise

    async def list(self, principal, cursor=None, limit=30):
        async with self.sessions() as session:
            query = select(KnowledgeDocument).where(
                KnowledgeDocument.tenant_id == principal.tenant_id,
                KnowledgeDocument.deleted.is_(False),
            )
            if principal.role == "customer":
                query = query.where(KnowledgeDocument.enabled.is_(True))
            if cursor:
                query = query.where(KnowledgeDocument.id < cursor)
            rows = list(
                await session.scalars(query.order_by(KnowledgeDocument.id.desc()).limit(limit + 1))
            )
            return {
                "items": [await self.dto(session, row) for row in rows[:limit]],
                "next_cursor": rows[limit - 1].id if len(rows) > limit else None,
            }

    async def get(self, principal, document_id):
        async with self.sessions() as session:
            return await self.dto(session, await self.row(session, principal, document_id))

    async def set_enabled(self, principal, document_id, enabled, version, request_id="-"):
        async with self.sessions() as session, session.begin():
            row = await self.row(session, principal, document_id, lock=True)
            if row.version != version:
                raise BusinessError("document_conflict", "文档状态已变化，请刷新", 409)
            row.enabled, row.version = enabled, row.version + 1
            AuditService.record(
                session,
                principal,
                "knowledge.enabled",
                "document",
                row.id,
                "success",
                request_id,
                {"enabled": enabled},
            )
            return await self.dto(session, row)

    async def delete(self, principal, document_id, request_id="-"):
        async with self.sessions() as session, session.begin():
            row = await self.row(session, principal, document_id, lock=True)
            row.deleted, row.enabled, row.version = True, False, row.version + 1
            AuditService.record(
                session, principal, "knowledge.deleted", "document", row.id, "success", request_id
            )

    async def download(self, principal, document_id, version_id=None):
        async with self.sessions() as session:
            row = await self.row(session, principal, document_id)
            active = await session.scalar(
                select(KnowledgeActiveVersion.version_id).where(
                    KnowledgeActiveVersion.tenant_id == principal.tenant_id,
                    KnowledgeActiveVersion.document_id == document_id,
                )
            )
            if principal.role == "customer" and (not row.enabled or not active):
                raise BusinessError("not_found", "文档不可访问", 404)
            query = select(DocumentVersion).where(
                DocumentVersion.tenant_id == principal.tenant_id,
                DocumentVersion.document_id == document_id,
            )
            if version_id is not None:
                query = query.where(DocumentVersion.id == version_id)
            elif active:
                query = query.where(DocumentVersion.id == active)
            version = await session.scalar(query.order_by(DocumentVersion.number.desc()).limit(1))
            if not version:
                raise BusinessError("not_found", "文档文件不存在", 404)
            if principal.role == "customer" and version.id != active:
                cited = await session.scalar(
                    select(Message.id)
                    .join(
                        Conversation,
                        (Conversation.tenant_id == Message.tenant_id)
                        & (Conversation.id == Message.conversation_id),
                    )
                    .where(
                        Message.tenant_id == principal.tenant_id,
                        Conversation.customer_id == principal.user_id,
                        Message.author_role == "ai",
                        func.json_contains(
                            Message.citations,
                            json.dumps([{"document_id": document_id, "version_id": version.id}]),
                        )
                        == 1,
                    )
                    .limit(1)
                )
                if not cited:
                    raise BusinessError("not_found", "文档版本不可访问", 404)
            path = self.storage.resolve(version.storage_key)
            if not path.is_file():
                raise BusinessError("file_unavailable", "文档文件暂时不可用", 503)
            return path, version.filename, version.media_type
