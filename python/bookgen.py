# =============================================================================
# The memory book. Plan section 6.9: "a PDF with a cover, chapters, photos, and a
# QR per chapter that plays the original voice."
#
# The book is the author's own words in the author's own order (features/
# stories.py). Nothing is rewritten and no heading is invented: a chapter's title
# is the caption the author wrote, or the prompt they answered.
#
# Rendering goes through HTML and WeasyPrint rather than a canvas library because
# the audience reads Devanagari, Tamil, Bengali and the rest, and only a real text
# shaper (Pango / HarfBuzz, which WeasyPrint uses) draws conjuncts and matras
# correctly; a library without shaping produces broken Hindi, which in a book of a
# grandmother's life is not a cosmetic defect. WeasyPrint is an optional
# dependency: without it the book is still built and stored as the same
# print-ready HTML, and the response says so honestly.
#
# QR codes point to an unguessable, expiring listen link and exist ONLY if the
# author asked for audio links (`include_audio`). A public link to somebody's voice
# is something a person chooses, chapter by chapter, never a default.
# =============================================================================
from __future__ import annotations

import hashlib
import hmac
import html
import logging
import time

from python import config
from python.integrations import storage

log = logging.getLogger("doosri.bookgen")

LISTEN_TTL_S = 365 * 86400
_DOMAIN = b"doosri:listen:v1:"

_CSS = """
@page { size: A5; margin: 18mm 16mm; @bottom-center { content: counter(page); font-size: 9pt; color: #666; } }
body { font-family: 'Noto Sans', 'Noto Sans Devanagari', 'Noto Sans Tamil', 'Noto Sans Bengali',
       'Noto Sans Telugu', 'Noto Sans Kannada', 'Noto Sans Malayalam', 'Noto Sans Gujarati',
       'Noto Sans Gurmukhi', sans-serif; font-size: 13pt; line-height: 1.55; color: #13201F; }
.cover { page-break-after: always; text-align: center; padding-top: 60mm; }
.cover h1 { font-size: 30pt; margin-bottom: 8mm; }
.cover p { font-size: 14pt; color: #3F4E4C; }
h2 { font-size: 18pt; margin: 0 0 6mm; page-break-before: always; }
.chapter p { margin: 0 0 4mm; white-space: pre-wrap; }
.qr { margin-top: 8mm; font-size: 10pt; color: #3F4E4C; }
.qr svg { width: 26mm; height: 26mm; }
.foot { margin-top: 12mm; font-size: 9pt; color: #666; }
"""


def _mac(payload: str) -> str:
    return hmac.new(config.SECRET_KEY.encode(), _DOMAIN + payload.encode(), hashlib.sha256).hexdigest()[:24]


def listen_token(story_id: int, ttl: int = LISTEN_TTL_S) -> str:
    exp = int(time.time()) + ttl
    payload = f"{story_id}.{exp}"
    return f"{payload}.{_mac(payload)}"


def read_listen_token(token: str) -> int | None:
    try:
        sid, exp, sig = token.split(".")
        if not hmac.compare_digest(sig, _mac(f"{sid}.{exp}")) or int(exp) < time.time():
            return None
        return int(sid)
    except (ValueError, AttributeError):
        return None


def _qr_svg(url: str) -> str:
    try:
        import io

        import segno  # noqa: PLC0415 - optional dependency

        buf = io.BytesIO()
        segno.make(url, error="m").save(buf, kind="svg", xmldecl=False, svgns=True, border=1, scale=3)
        return buf.getvalue().decode()
    except Exception:
        return ""


def build_html(title: str, author: str, chapters: list[dict], include_audio: bool) -> str:
    """chapters: [{"title", "text", "story_id", "audio": bool}]"""
    parts = [f"<html><head><meta charset='utf-8'><style>{_CSS}</style><title>{html.escape(title)}</title></head><body>"]
    parts.append(f"<section class='cover'><h1>{html.escape(title)}</h1><p>{html.escape(author)}</p></section>")
    for ch in chapters:
        parts.append(f"<section class='chapter'><h2>{html.escape(ch['title'] or '')}</h2>")
        for para in (ch.get("text") or "").split("\n\n"):
            if para.strip():
                parts.append(f"<p>{html.escape(para.strip())}</p>")
        if include_audio and ch.get("audio"):
            link = f"{config.PUBLIC_BASE_URL}/listen/{listen_token(ch['story_id'])}"
            svg = _qr_svg(link)
            if svg:
                parts.append(f"<div class='qr'>{svg}<div>Scan to hear this chapter in my own voice.</div></div>")
        parts.append("</section>")
    parts.append("<p class='foot'>Made with Doosri Pari — an independent app, not affiliated with any government department.</p>")
    parts.append("</body></html>")
    return "".join(parts)


def render(user_id: int, book_id: int, title: str, author: str, chapters: list[dict], include_audio: bool) -> dict | None:
    """
    Build and store the book. Returns {"key", "format", "url"} or None if nothing
    could be stored. `format` is "pdf" when WeasyPrint is available, else "html".
    """
    doc = build_html(title, author, chapters, include_audio)
    fmt, data, ctype = "html", doc.encode("utf-8"), "text/html"
    try:
        from weasyprint import HTML  # noqa: PLC0415 - optional dependency

        data, fmt, ctype = HTML(string=doc).write_pdf(), "pdf", "application/pdf"
    except Exception as exc:
        log.info("weasyprint unavailable (%s); storing the print-ready HTML instead", type(exc).__name__)
    key = f"books/{user_id}/{book_id}-{int(time.time())}.{fmt}"
    if not storage.put_bytes(key, data, ctype):
        return None
    return {"key": key, "format": fmt, "url": storage.presign_download(key, 3600)}
