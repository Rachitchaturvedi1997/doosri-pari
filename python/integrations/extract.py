# =============================================================================
# Document text extraction and chunking for the Sahayak corpus. Plan 6.4.
#
# Extraction: pdfplumber for text PDFs; if a page has no text layer it is a scan
# and is OCR'd with Tesseract (Hindi + English). Both are optional dependencies,
# imported late. A page that cannot be read yields "" and the document is left
# with fewer chunks rather than failing — the admin review queue is where a
# thin extraction is caught, because nothing goes live unreviewed.
#
# Chunking: heading-aware, one rule or sub-rule per chunk, 200-600 tokens. A
# "token" here is a whitespace-separated word, which for English and Hindi runs
# within 20-30% of a model token and is the same measure used at query time.
# =============================================================================
from __future__ import annotations

import io
import logging
import re

log = logging.getLogger("doosri.extract")

MIN_TOKENS = 200
MAX_TOKENS = 600
TARGET_TOKENS = 420

# "Rule 44", "44.", "(1)", "CHAPTER IV", "PART II", "Annexure A", "Table C".
_HEADING = re.compile(
    r"^\s*(?:"
    r"(?:CHAPTER|PART|SECTION|ANNEXURE|APPENDIX|SCHEDULE)\s+[A-Z0-9IVXL]+.*"
    r"|(?:Rule|Regulation|Para(?:graph)?|Article)\s+\d+[A-Za-z]?(?:\(\d+\))?.*"
    r"|\d{1,3}\.\s+[A-Z][^.]{3,80}"
    r"|Table\s+[A-Z0-9]+.*"
    r")\s*$",
    re.IGNORECASE,
)
_RULE_START = re.compile(r"^\s*(\d{1,3}[A-Z]?)\.\s+\S")


def pdf_to_pages(data: bytes) -> list[tuple[int, str]]:
    """[(page_number, text)] in reading order; page numbers start at 1."""
    pages: list[tuple[int, str]] = []
    try:
        import pdfplumber  # noqa: PLC0415 — optional dependency
    except ImportError:
        log.warning("pdfplumber is not installed; cannot extract PDF text")
        return pages
    try:
        with pdfplumber.open(io.BytesIO(data)) as pdf:
            for i, page in enumerate(pdf.pages, start=1):
                text = (page.extract_text() or "").strip()
                if len(text) < 40:
                    text = _ocr_page(page) or text
                if text:
                    pages.append((i, text))
    except Exception as exc:
        log.warning("pdf extraction failed: %s", type(exc).__name__)
    return pages


def _ocr_page(page) -> str:  # noqa: ANN001
    try:
        import pytesseract  # noqa: PLC0415 — optional dependency
    except ImportError:
        return ""
    try:
        img = page.to_image(resolution=200).original
        return pytesseract.image_to_string(img, lang="hin+eng").strip()
    except Exception as exc:
        log.warning("ocr failed: %s", type(exc).__name__)
        return ""


def html_to_text(html: str) -> str:
    """Visible text from an HTML page, no third-party parser."""
    from html.parser import HTMLParser

    class _P(HTMLParser):
        def __init__(self) -> None:
            super().__init__()
            self.out: list[str] = []
            self.skip = 0

        def handle_starttag(self, tag, attrs):  # noqa: ANN001
            if tag in ("script", "style", "nav", "footer", "header", "noscript"):
                self.skip += 1
            if tag in ("p", "br", "li", "tr", "div", "h1", "h2", "h3", "h4"):
                self.out.append("\n")

        def handle_endtag(self, tag):  # noqa: ANN001
            if tag in ("script", "style", "nav", "footer", "header", "noscript") and self.skip:
                self.skip -= 1

        def handle_data(self, data):  # noqa: ANN001
            if not self.skip:
                self.out.append(data)

    p = _P()
    p.feed(html)
    text = "".join(p.out)
    return re.sub(r"\n\s*\n+", "\n\n", re.sub(r"[ \t]+", " ", text)).strip()


def _tokens(s: str) -> int:
    return len(s.split())


def chunk_pages(pages: list[tuple[int, str]]) -> list[dict]:
    """
    Heading-aware chunks. Each is {"heading_path", "text", "page"}.

    Walks the lines keeping the current heading trail. A chunk closes at a new
    rule/heading once it has reached MIN_TOKENS, or when it would exceed
    MAX_TOKENS (split at the last sentence end, so a rule is not cut mid-clause
    where avoidable). Short trailing text is merged into the previous chunk of
    the same heading rather than emitted as a fragment.
    """
    chunks: list[dict] = []
    trail: list[str] = []
    buf: list[str] = []
    buf_page = pages[0][0] if pages else 1

    def flush() -> None:
        # In place: _split_at_sentence holds the same list and appends to it
        # after calling us, so rebinding `buf` here would silently drop text.
        text = " ".join(buf).strip()
        buf.clear()
        if not text:
            return
        path = " > ".join(trail) if trail else "Preamble"
        if chunks and _tokens(text) < MIN_TOKENS // 2 and chunks[-1]["heading_path"] == path:
            chunks[-1]["text"] += " " + text
            return
        chunks.append({"heading_path": path[:300], "text": text, "page": buf_page})

    for page_no, text in pages:
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            is_heading = bool(_HEADING.match(line)) and len(line) < 110
            if is_heading:
                if _tokens(" ".join(buf)) >= MIN_TOKENS // 2:
                    flush()
                _push_heading(trail, line)
                buf_page = page_no
                continue
            if not buf:
                buf_page = page_no
            buf.append(line)
            if _tokens(" ".join(buf)) >= MAX_TOKENS:
                _split_at_sentence(buf, flush_fn=flush)
    flush()
    return chunks


def _push_heading(trail: list[str], line: str) -> None:
    """Maintain 'PART IV > Rule 12 > Table C' style trails."""
    up = line.upper()
    level = 0
    if up.startswith(("CHAPTER", "PART", "SCHEDULE")):
        level = 0
    elif up.startswith(("SECTION", "ANNEXURE", "APPENDIX")):
        level = 1
    elif up.startswith(("TABLE",)):
        level = 3
    else:
        level = 2
    del trail[level:]
    while len(trail) < level:
        trail.append("")
    trail.append(line[:100])
    while trail and trail[0] == "":
        trail.pop(0)
    trail[:] = [t for t in trail if t]


def _split_at_sentence(buf: list[str], flush_fn) -> None:  # noqa: ANN001
    """Close the buffer at the last sentence end before TARGET_TOKENS."""
    text = " ".join(buf)
    words = text.split()
    head = " ".join(words[:TARGET_TOKENS])
    cut = max(head.rfind(". "), head.rfind("। "), head.rfind("; "))
    if cut < len(head) * 0.5:
        cut = len(head)
    first, rest = text[: cut + 1].strip(), text[cut + 1 :].strip()
    buf[:] = [first]
    flush_fn()
    buf.append(rest)
