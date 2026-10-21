import asyncio
import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

from servicehub.core.errors import BusinessError

MEDIA = {".txt": "text/plain", ".md": "text/markdown", ".pdf": "application/pdf"}


@dataclass(frozen=True)
class StoredUpload:
    key: str
    filename: str
    media_type: str
    size: int
    sha256: str


class PrivateStorage:
    def __init__(self, settings):
        self.root, self.limit = settings.upload_path, settings.max_upload_bytes

    def resolve(self, key):
        if not re.fullmatch(r"[a-f0-9]{32}\.(txt|md|pdf)", key):
            raise ValueError("Invalid private storage key")
        path = (self.root / key).resolve()
        if not path.is_relative_to(self.root.resolve()):
            raise ValueError("Storage path outside private root")
        return path

    async def save(self, upload):
        filename = (upload.filename or "").replace("\\", "/").split("/")[-1][:255]
        extension = Path(filename).suffix.lower()
        if extension not in MEDIA:
            raise BusinessError(
                "unsupported_document", "仅支持 UTF-8 TXT、Markdown 和文本 PDF", 415
            )
        key = uuid4().hex + extension
        path = self.resolve(key)
        await asyncio.to_thread(self.root.mkdir, parents=True, exist_ok=True)
        size, digest = 0, hashlib.sha256()
        try:
            with path.open("xb") as stream:
                while chunk := await upload.read(256 * 1024):
                    size += len(chunk)
                    if size > self.limit:
                        raise BusinessError("upload_too_large", "单个文件不能超过 10 MiB", 413)
                    digest.update(chunk)
                    await asyncio.to_thread(stream.write, chunk)
            if size == 0:
                raise BusinessError("empty_upload", "文件内容为空", 422)
        except Exception:
            path.unlink(missing_ok=True)
            raise
        return StoredUpload(key, filename, MEDIA[extension], size, digest.hexdigest())
