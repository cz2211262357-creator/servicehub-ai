class PermanentJobError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class RetryableJobError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def build_handlers(sessions, redis, settings):
    from servicehub.knowledge.indexer import KnowledgeIndexer

    indexer = KnowledgeIndexer(sessions, redis, settings)

    async def knowledge_index(lease):
        return await indexer.build_version(lease, lease.payload["document_version_id"])

    from servicehub.ai.service import AIService

    ai = AIService(sessions, redis, settings)
    return {"knowledge_index": knowledge_index, "ai_reply": ai.handle}
