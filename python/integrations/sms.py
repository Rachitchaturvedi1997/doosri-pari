# =============================================================================
# SMS. Plan section 9: DLT-registered templates via MSG91 or Fast2SMS.
#
# Indian carriers only deliver transactional SMS whose text matches a template
# registered under DLT, so "send this string" is not something a provider will
# do. Each message kind therefore maps to a template id (config.MSG91_TEMPLATES)
# and the free text travels as template variables. Twilio has no DLT step and is
# supported for development and for guardians abroad.
# =============================================================================
from __future__ import annotations

import logging

from python import config
from python.integrations import _http
from python.notify import SendResult

log = logging.getLogger("doosri.sms")


def _digits(phone: str | None) -> str:
    return "".join(ch for ch in (phone or "") if ch in "0123456789")


def _msisdn_in(phone: str | None) -> str:
    """Country code first, no plus: '919812345678'."""
    d = _digits(phone)
    if len(d) == 10:
        return "91" + d
    return d


def template_for(kind: str) -> str:
    head = (kind or "").split(":", 1)[0]
    mapping = {
        "otp": "otp",
        "sos": "sos",
        "sos-cancel": "all_clear",
        "all_clear": "all_clear",
        "all_fine": "all_clear",
        "invite": "invite",
        "guardian": "guardian_alert",
        "alert": "guardian_alert",
    }
    key = mapping.get(head, "generic")
    return config.MSG91_TEMPLATES.get(key) or config.MSG91_TEMPLATES.get("generic", "")


class SmsChannel:
    name = "sms"

    def send(self, *, to, title, body, kind="", **kw) -> SendResult:  # noqa: ANN001
        if not to:
            return SendResult(False, "sms", "no phone number")
        provider = config.SMS_PROVIDER
        if provider == "msg91":
            return self._msg91(to, title, body, kind)
        if provider == "fast2sms":
            return self._fast2sms(to, body)
        if provider == "twilio":
            return self._twilio(to, body)
        return SendResult(False, "sms", "no sms provider configured")

    # MSG91 Flow API: one template, variables var1/var2 (DLT-approved).
    def _msg91(self, to: str, title: str, body: str, kind: str) -> SendResult:
        template = template_for(kind)
        if not (config.MSG91_AUTH_KEY and template):
            return SendResult(False, "sms", "msg91 not configured for this template")
        r = _http.request(
            "POST",
            "https://control.msg91.com/api/v5/flow/",
            headers={"authkey": config.MSG91_AUTH_KEY, "content-type": "application/json"},
            json={
                "template_id": template,
                "short_url": "0",
                "recipients": [{"mobiles": _msisdn_in(to), "var1": title[:30], "var2": body[:160]}],
            },
        )
        if r.ok and isinstance(r.body, dict) and r.body.get("type") != "error":
            return SendResult(True, "sms", "msg91", provider_id=str(r.body.get("message", ""))[:60])
        return SendResult(False, "sms", f"msg91 {r.status} {r.error}"[:120])

    # Fast2SMS DLT route.
    def _fast2sms(self, to: str, body: str) -> SendResult:
        if not config.FAST2SMS_API_KEY:
            return SendResult(False, "sms", "fast2sms not configured")
        r = _http.request(
            "POST",
            "https://www.fast2sms.com/dev/bulkV2",
            headers={"authorization": config.FAST2SMS_API_KEY},
            json={
                "route": "dlt",
                "sender_id": config.MSG91_SENDER_ID,
                "message": config.MSG91_TEMPLATES.get("generic", ""),
                "variables_values": body[:160],
                "numbers": _digits(to)[-10:],
            },
        )
        if r.ok and isinstance(r.body, dict) and r.body.get("return") is True:
            return SendResult(True, "sms", "fast2sms", provider_id=str(r.body.get("request_id", ""))[:60])
        return SendResult(False, "sms", f"fast2sms {r.status}")

    def _twilio(self, to: str, body: str) -> SendResult:
        if not (config.TWILIO_ACCOUNT_SID and config.TWILIO_AUTH_TOKEN and config.TWILIO_FROM):
            return SendResult(False, "sms", "twilio not configured")
        number = to if to.startswith("+") else "+" + _msisdn_in(to)
        r = _http.request(
            "POST",
            f"https://api.twilio.com/2010-04-01/Accounts/{config.TWILIO_ACCOUNT_SID}/Messages.json",
            auth=(config.TWILIO_ACCOUNT_SID, config.TWILIO_AUTH_TOKEN),
            data={"To": number, "From": config.TWILIO_FROM, "Body": body[:600]},
        )
        if r.ok and isinstance(r.body, dict):
            return SendResult(True, "sms", "twilio", provider_id=str(r.body.get("sid", ""))[:60])
        return SendResult(False, "sms", f"twilio {r.status}")


def send_otp(to_e164: str, code: str) -> bool:
    """
    The OTP path. It does not go through notify (there is no user row yet), so it
    calls the provider directly and reports only whether it was accepted.
    """
    text = f"Your Doosri Pari code is {code}. It is valid for 10 minutes."
    ch = SmsChannel()
    if config.SMS_PROVIDER == "msg91":
        template = config.MSG91_TEMPLATES.get("otp")
        if not (config.MSG91_AUTH_KEY and template):
            return False
        r = _http.request(
            "POST",
            "https://control.msg91.com/api/v5/flow/",
            headers={"authkey": config.MSG91_AUTH_KEY, "content-type": "application/json"},
            json={
                "template_id": template,
                "recipients": [{"mobiles": _msisdn_in(to_e164), "var1": code}],
            },
        )
        return bool(r.ok and isinstance(r.body, dict) and r.body.get("type") != "error")
    return ch.send(to=to_e164, title="Doosri Pari", body=text, kind="otp").ok
