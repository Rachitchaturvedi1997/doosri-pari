# =============================================================================
# WhatsApp Business Cloud API. Plan sections 6.3 and 9.
#
# Business-initiated messages must be pre-approved templates, so like SMS this
# sends a template with variables rather than free text. Two templates exist:
# an alert (a missed check-in, an SOS) and an all-clear. The variables are
# {{1}} = who, {{2}} = what happened.
# =============================================================================
from __future__ import annotations

import logging

from python import config
from python.integrations import _http
from python.notify import SendResult

log = logging.getLogger("doosri.whatsapp")


def _msisdn(phone: str | None) -> str:
    d = "".join(ch for ch in (phone or "") if ch in "0123456789")
    return "91" + d if len(d) == 10 else d


class WhatsAppChannel:
    name = "whatsapp"

    def send(self, *, to, title, body, kind="", **kw) -> SendResult:  # noqa: ANN001
        if not to:
            return SendResult(False, "whatsapp", "no phone number")
        if not (config.WHATSAPP_TOKEN and config.WHATSAPP_PHONE_ID):
            return SendResult(False, "whatsapp", "whatsapp not configured")

        head = (kind or "").split(":", 1)[0]
        clear = head in ("all_clear", "all_fine", "sos-cancel")
        template = config.WHATSAPP_TEMPLATE_ALL_CLEAR if clear else config.WHATSAPP_TEMPLATE_ALERT

        r = _http.request(
            "POST",
            f"https://graph.facebook.com/v20.0/{config.WHATSAPP_PHONE_ID}/messages",
            headers={"Authorization": f"Bearer {config.WHATSAPP_TOKEN}"},
            json={
                "messaging_product": "whatsapp",
                "to": _msisdn(to),
                "type": "template",
                "template": {
                    "name": template,
                    "language": {"code": "en"},
                    "components": [
                        {
                            "type": "body",
                            "parameters": [
                                {"type": "text", "text": title[:60] or "Doosri Pari"},
                                {"type": "text", "text": body[:400]},
                            ],
                        }
                    ],
                },
            },
        )
        if r.ok and isinstance(r.body, dict):
            msgs = r.body.get("messages") or [{}]
            return SendResult(True, "whatsapp", "cloud api", provider_id=str(msgs[0].get("id", ""))[:60])
        err = ""
        if isinstance(r.body, dict):
            err = str((r.body.get("error") or {}).get("code", ""))
        return SendResult(False, "whatsapp", f"whatsapp {r.status} {err}"[:120])
