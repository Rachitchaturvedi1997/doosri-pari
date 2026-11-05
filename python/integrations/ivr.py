# =============================================================================
# IVR — the automated "if you are fine, press 1" call. Plan section 6.3.
#
# Placing the call is the vendor's job (Twilio or Exotel). What this file adds is
# the part that must not be forgeable: the URL the vendor calls back on carries
# a signed token naming exactly one check-in, so a stranger who learns the
# webhook address cannot press "1" on someone else's behalf.
#
#   token = "<checkin_id>.<user_id>.<expires>.<hmac>"
#
# The HMAC is keyed with SECRET_KEY and is domain-separated from every other
# digest in the app (see python/security.py for the same discipline).
#
# The webhook routes themselves live in python/features/ivr.py.
# =============================================================================
from __future__ import annotations

import hashlib
import hmac
import logging
import time

from python import config
from python.integrations import _http
from python.notify import SendResult

log = logging.getLogger("doosri.ivr")

_DOMAIN = b"doosri:ivr:v1:"

# Spoken prompt, by language. Twilio's <Say> takes a BCP-47 language; Exotel's
# flow plays a recording chosen by the same code.
PROMPTS = {
    "en": "Namaste. This is Doosri Pari. If you are fine, press 1. To speak to your family, press 2.",
    "hi": "नमस्ते। यह दूसरी पारी है। अगर आप ठीक हैं, तो 1 दबाइए। परिवार से बात करनी हो, तो 2 दबाइए।",
    "mr": "नमस्कार. हे दुसरी पारी आहे. तुम्ही ठीक असाल तर १ दाबा. कुटुंबाशी बोलायचे असल्यास २ दाबा.",
    "bn": "নমস্কার। এটি দুসরি পারি। আপনি ভালো থাকলে ১ চাপুন। পরিবারের সঙ্গে কথা বলতে ২ চাপুন।",
    "ta": "வணக்கம். இது தூஸ்ரி பாரி. நீங்கள் நலமாக இருந்தால் 1 ஐ அழுத்தவும். குடும்பத்துடன் பேச 2 ஐ அழுத்தவும்.",
    "te": "నమస్కారం. ఇది దూస్రి పారి. మీరు క్షేమంగా ఉంటే 1 నొక్కండి. కుటుంబంతో మాట్లాడాలంటే 2 నొక్కండి.",
    "kn": "ನಮಸ್ಕಾರ. ಇದು ದೂಸ್ರಿ ಪಾರಿ. ನೀವು ಚೆನ್ನಾಗಿದ್ದರೆ 1 ಒತ್ತಿ. ಕುಟುಂಬದೊಂದಿಗೆ ಮಾತನಾಡಲು 2 ಒತ್ತಿ.",
    "ml": "നമസ്കാരം. ഇത് ദൂസ്രി പാരി ആണ്. നിങ്ങൾ സുഖമായിരിക്കുന്നെങ്കിൽ 1 അമർത്തുക. കുടുംബത്തോട് സംസാരിക്കാൻ 2 അമർത്തുക.",
    "gu": "નમસ્તે. આ દૂસરી પારી છે. તમે સારા હો તો 1 દબાવો. પરિવાર સાથે વાત કરવા 2 દબાવો.",
    "pa": "ਸਤ ਸ੍ਰੀ ਅਕਾਲ। ਇਹ ਦੂਜੀ ਪਾਰੀ ਹੈ। ਜੇ ਤੁਸੀਂ ਠੀਕ ਹੋ ਤਾਂ 1 ਦਬਾਓ। ਪਰਿਵਾਰ ਨਾਲ ਗੱਲ ਕਰਨ ਲਈ 2 ਦਬਾਓ।",
}
SAY_LANG = {
    "en": "en-IN", "hi": "hi-IN", "mr": "mr-IN", "bn": "bn-IN", "ta": "ta-IN",
    "te": "te-IN", "kn": "kn-IN", "ml": "ml-IN", "gu": "gu-IN", "pa": "pa-IN",
}
THANKS = {
    "en": "Thank you. Your family will be told you are fine. Goodbye.",
    "hi": "धन्यवाद। आपके परिवार को बता दिया जाएगा कि आप ठीक हैं। नमस्ते।",
}


def _sign(payload: str) -> str:
    return hmac.new(
        config.SECRET_KEY.encode("utf-8"), _DOMAIN + payload.encode("utf-8"), hashlib.sha256
    ).hexdigest()[:32]


def make_token(checkin_id: int, user_id: int, ttl: int | None = None) -> str:
    exp = int(time.time()) + (ttl or config.IVR_TOKEN_TTL_S)
    payload = f"{checkin_id}.{user_id}.{exp}"
    return f"{payload}.{_sign(payload)}"


def read_token(token: str) -> tuple[int, int] | None:
    """(checkin_id, user_id) if the token is genuine and unexpired, else None."""
    try:
        cid, uid, exp, sig = token.split(".")
        payload = f"{cid}.{uid}.{exp}"
        if not hmac.compare_digest(sig, _sign(payload)):
            return None
        if int(exp) < time.time():
            return None
        return int(cid), int(uid)
    except (ValueError, AttributeError):
        return None


def prompt_for(lang: str) -> str:
    return PROMPTS.get(lang) or PROMPTS["en"]


def thanks_for(lang: str) -> str:
    return THANKS.get(lang) or THANKS["en"]


def say_lang(lang: str) -> str:
    return SAY_LANG.get(lang, "en-IN")


class IvrChannel:
    name = "ivr_call"

    def send(self, *, to, title, body, payload=None, **kw) -> SendResult:  # noqa: ANN001
        if not to:
            return SendResult(False, "ivr_call", "no phone number")
        token = (payload or {}).get("ivr_token")
        if not token:
            return SendResult(False, "ivr_call", "no ivr token")
        provider = config.IVR_PROVIDER
        if provider == "twilio":
            return self._twilio(to, token)
        if provider == "exotel":
            return self._exotel(to, token)
        return SendResult(False, "ivr_call", "no ivr provider configured")

    def _twilio(self, to: str, token: str) -> SendResult:
        if not (config.TWILIO_ACCOUNT_SID and config.TWILIO_AUTH_TOKEN and config.TWILIO_FROM):
            return SendResult(False, "ivr_call", "twilio not configured")
        number = to if to.startswith("+") else "+91" + "".join(c for c in to if c.isdigit())[-10:]
        r = _http.request(
            "POST",
            f"https://api.twilio.com/2010-04-01/Accounts/{config.TWILIO_ACCOUNT_SID}/Calls.json",
            auth=(config.TWILIO_ACCOUNT_SID, config.TWILIO_AUTH_TOKEN),
            data={
                "To": number,
                "From": config.TWILIO_FROM,
                "Url": f"{config.PUBLIC_BASE_URL}/ivr/twiml/{token}",
                "Timeout": "25",
                "StatusCallback": f"{config.PUBLIC_BASE_URL}/ivr/status/{token}",
            },
        )
        if r.ok and isinstance(r.body, dict):
            return SendResult(True, "ivr_call", "twilio", provider_id=str(r.body.get("sid", ""))[:60])
        return SendResult(False, "ivr_call", f"twilio {r.status}")

    def _exotel(self, to: str, token: str) -> SendResult:
        if not (config.EXOTEL_SID and config.EXOTEL_API_KEY and config.EXOTEL_API_TOKEN):
            return SendResult(False, "ivr_call", "exotel not configured")
        url = f"https://{config.EXOTEL_SUBDOMAIN}/v1/Accounts/{config.EXOTEL_SID}/Calls/connect.json"
        r = _http.request(
            "POST",
            url,
            auth=(config.EXOTEL_API_KEY, config.EXOTEL_API_TOKEN),
            data={
                "From": "".join(c for c in to if c.isdigit())[-10:],
                "CallerId": config.EXOTEL_CALLER_ID,
                "Url": config.EXOTEL_FLOW_URL,
                "CustomField": token,
                "StatusCallback": f"{config.PUBLIC_BASE_URL}/ivr/status/{token}",
                "TimeLimit": "90",
                "TimeOut": "25",
            },
        )
        if r.ok and isinstance(r.body, dict):
            sid = ((r.body.get("Call") or {}).get("Sid")) or ""
            return SendResult(True, "ivr_call", "exotel", provider_id=str(sid)[:60])
        return SendResult(False, "ivr_call", f"exotel {r.status}")
