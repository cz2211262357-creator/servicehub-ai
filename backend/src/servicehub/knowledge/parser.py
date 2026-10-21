from dataclasses import dataclass
from pathlib import Path

from pypdf import PdfReader


@dataclass(frozen=True)
class ParsedPage:
    page: int
    text: str


class DocumentParseError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def parse_document(path: Path, media_type: str) -> list[ParsedPage]:
    raw = path.read_bytes()
    if media_type == "application/pdf":
        if not raw.startswith(b"%PDF-"):
            raise DocumentParseError("invalid_pdf")
        try:
            reader = PdfReader(path)
            if reader.is_encrypted or len(reader.pages) > 500:
                raise DocumentParseError("pdf_not_supported")
            pages = [
                ParsedPage(i + 1, page.extract_text().strip())
                for i, page in enumerate(reader.pages)
            ]
        except DocumentParseError:
            raise
        except Exception:
            raise DocumentParseError("invalid_pdf") from None
        pages = [page for page in pages if page.text]
        if not pages:
            raise DocumentParseError("pdf_no_text")
        return pages
    if (
        media_type not in ("text/plain", "text/markdown")
        or b"\x00" in raw
        or raw.startswith(b"%PDF-")
    ):
        raise DocumentParseError("invalid_text")
    try:
        text = raw.decode("utf-8-sig").strip()
    except UnicodeError:
        raise DocumentParseError("invalid_utf8") from None
    if not text:
        raise DocumentParseError("empty_document")
    return [ParsedPage(1, text)]


def split_pages(pages, size=800, overlap=100):
    for page in pages:
        for offset in range(0, len(page.text), size - overlap):
            text = page.text[offset : offset + size].strip()
            if text:
                yield page.page, text
