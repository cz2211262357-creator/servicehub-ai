import pytest
from pypdf import PdfWriter


def test_text_pages_and_pdf_disguise_are_validated(tmp_path):
    from servicehub.knowledge.parser import DocumentParseError, parse_document

    path = tmp_path / "policy.md"
    path.write_text("七天内未使用商品支持退货。\n请保留完整包装。", encoding="utf-8")
    pages = parse_document(path, "text/markdown")
    assert pages[0].page == 1 and "完整包装" in pages[0].text
    path.write_bytes(b"\x89PNG\r\n\x1a\n\x00fake")
    with pytest.raises(DocumentParseError):
        parse_document(path, "text/markdown")
    with pytest.raises(DocumentParseError):
        parse_document(path, "application/pdf")


def test_scanned_or_empty_pdf_does_not_enable_empty_index(tmp_path):
    from servicehub.knowledge.parser import DocumentParseError, parse_document

    writer = PdfWriter()
    writer.add_blank_page(width=200, height=200)
    path = tmp_path / "scanned.pdf"
    with path.open("wb") as stream:
        writer.write(stream)
    with pytest.raises(DocumentParseError) as error:
        parse_document(path, "application/pdf")
    assert error.value.code == "pdf_no_text"
