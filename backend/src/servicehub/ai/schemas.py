from typing import Protocol

from pydantic import BaseModel


class EmbeddingProvider(Protocol):
    async def embed(self, texts: list[str]) -> list[list[float]]: ...


class Evidence(BaseModel):
    chunk_id: int
    document_id: int
    version_id: int
    page: int
    text: str
    score: float
    title: str = ""
