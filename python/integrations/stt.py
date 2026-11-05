# =============================================================================
# Speech to text for voice notes and memoir chapters. Plan sections 6.6 and 6.9.
#
# The on-device recogniser handles the live "Bolo" mic; this is the server pass
# for uploaded audio, so a voice post can be moderated (a moderator needs words
# to read) and a memoir chapter has editable text.
#
# Two providers:
#   * "whisper" — any OpenAI-compatible /audio/transcriptions endpoint. Handles
#     Hindi and the other launch languages, takes files to 25 MB.
#   * "google"  — Google Cloud Speech-to-Text REST, with an API key, for clips
#     under a minute; longer audio is refused here and the caller leaves the row
#     for a provider that can take it (returning None is "not transcribed", and
#     jobs.transcribe tries again rather than writing an empty transcript).
# =============================================================================
from __future__ import annotations

import base64
import logging

from python import config
from python.integrations import _http, storage

log = logging.getLogger("doosri.stt")

# Google wants BCP-47 codes; the app stores two-letter ones.
GOOGLE_LANG = {
    "en": "en-IN", "hi": "hi-IN", "mr": "mr-IN", "bn": "bn-IN", "ta": "ta-IN",
    "te": "te-IN", "kn": "kn-IN", "ml": "ml-IN", "gu": "gu-IN", "pa": "pa-Guru-IN",
}
_EXT_TYPE = {"m4a": "audio/mp4", "aac": "audio/aac", "mp3": "audio/mpeg", "ogg": "audio/ogg", "webm": "audio/webm"}


def available() -> bool:
    return bool(config.STT_PROVIDER)


def transcribe(media_key: str, language: str) -> str | None:
    """The transcript, or None when it could not be produced. Never raises."""
    if not config.STT_PROVIDER:
        return None
    data = storage.get_bytes(media_key)
    if not data:
        log.warning("stt: media not found")
        return None
    ext = media_key.rsplit(".", 1)[-1].lower()
    ctype = _EXT_TYPE.get(ext, "application/octet-stream")

    if config.STT_PROVIDER == "whisper":
        return _whisper(data, ext, ctype, language)
    if config.STT_PROVIDER == "google":
        return _google(data, ext, language)
    return None


def _whisper(data: bytes, ext: str, ctype: str, language: str) -> str | None:
    if not config.STT_KEY:
        return None
    r = _http.request(
        "POST",
        config.STT_URL,
        headers={"authorization": f"Bearer {config.STT_KEY}"},
        files={"file": (f"audio.{ext}", data, ctype)},
        data={"model": config.STT_MODEL, "language": language[:2], "response_format": "json"},
        timeout=300.0,
        retries=1,
    )
    if r.ok and isinstance(r.body, dict):
        return (r.body.get("text") or "").strip() or None
    log.warning("whisper failed: status=%s", r.status)
    return None


def _google(data: bytes, ext: str, language: str) -> str | None:
    if not config.GOOGLE_SPEECH_API_KEY:
        return None
    if len(data) > 900_000:
        # Inline audio is limited to about a minute; do not pretend otherwise.
        log.info("stt google: clip too long for inline recognition")
        return None
    encoding = {"webm": "WEBM_OPUS", "ogg": "OGG_OPUS", "mp3": "MP3"}.get(ext, "ENCODING_UNSPECIFIED")
    cfg = {
        "languageCode": GOOGLE_LANG.get(language, "hi-IN"),
        "enableAutomaticPunctuation": True,
        "encoding": encoding,
    }
    if language != "en":
        cfg["alternativeLanguageCodes"] = ["en-IN"]
    r = _http.request(
        "POST",
        "https://speech.googleapis.com/v1/speech:recognize",
        params={"key": config.GOOGLE_SPEECH_API_KEY},
        json={"config": cfg, "audio": {"content": base64.b64encode(data).decode()}},
        timeout=60.0,
    )
    if not r.ok or not isinstance(r.body, dict):
        log.warning("google stt failed: status=%s", r.status)
        return None
    parts = []
    for res in r.body.get("results", []):
        alts = res.get("alternatives") or []
        if alts:
            parts.append(alts[0].get("transcript", ""))
    text = " ".join(p.strip() for p in parts if p.strip())
    return text or None
