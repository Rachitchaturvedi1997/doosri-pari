# =============================================================================
# Push: FCM (Android) and APNs (iOS). Plan sections 6.3, 6.15 and 8.3.
#
# One channel, two transports, chosen by the device's platform. The urgency
# handling is the plan's, not the vendors':
#
#   Android  urgent  -> FCM priority HIGH on the "safety" channel, which the app
#                       declares with full-screen-intent permission.
#   iOS      urgent  -> interruption-level "critical" when Apple has granted the
#                       entitlement (APNS_CRITICAL_ALERTS), otherwise
#                       "time-sensitive". Falling back is not a downgrade we
#                       hide: the plan (section 8.3) names it as the designed
#                       fallback.
#   anything else    -> normal priority, "active" on iOS.
#
# A token the vendor says is dead (UNREGISTERED / BadDeviceToken) is reported in
# SendResult.detail as "dead_token" so the dispatcher can delete the Device row
# instead of trying it forever.
# =============================================================================
from __future__ import annotations

import logging
import time

from python import config
from python.integrations import _google, _http
from python.integrations._secrets import TokenCache, read_text
from python.notify import SendResult

log = logging.getLogger("doosri.push")

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
APNS_HOST = "https://api.push.apple.com"
APNS_SANDBOX_HOST = "https://api.sandbox.push.apple.com"

# Android notification channels, declared by the app (plan 6.15). The ids are
# part of the wire contract with the client.
CHANNEL_FOR_KIND = {
    "checkin": "safety",
    "remind": "safety",
    "sos": "safety",
    "all_clear": "safety",
    "all_fine": "safety",
    "guardian": "safety",
    "alert": "safety",
    "dose": "medicines",
    "medicine": "medicines",
    "pension": "pension",
    "life_certificate": "pension",
    "circle": "circles",
    "digest": "circles",
    "seva": "seva",
    "practice": "practice",
}


def channel_id(kind: str) -> str:
    head = (kind or "").split(":", 1)[0]
    for prefix, channel in CHANNEL_FOR_KIND.items():
        if head.startswith(prefix):
            return channel
    return "circles"


class FcmTransport:
    def send(self, token: str, title: str, body: str, urgent: bool, payload: dict, kind: str) -> SendResult:
        if not config.FCM_PROJECT_ID or not config.FCM_SERVICE_ACCOUNT:
            return SendResult(False, "push", "fcm not configured")
        bearer = _google.access_token(config.FCM_SERVICE_ACCOUNT, FCM_SCOPE)
        if not bearer:
            return SendResult(False, "push", "fcm token unavailable")

        data = {str(k): str(v) for k, v in (payload or {}).items()}
        data["kind"] = kind or ""
        message = {
            "token": token,
            "notification": {"title": title, "body": body},
            "data": data,
            "android": {
                "priority": "HIGH" if urgent else "NORMAL",
                "notification": {
                    "channel_id": channel_id(kind),
                    "sound": "default",
                    # Shown on the lock screen even for a private notification;
                    # a missed check-in is not a secret from the person holding
                    # the phone.
                    "visibility": "PUBLIC",
                },
                "ttl": "3600s" if urgent else "86400s",
            },
        }
        r = _http.request(
            "POST",
            f"https://fcm.googleapis.com/v1/projects/{config.FCM_PROJECT_ID}/messages:send",
            headers={"Authorization": f"Bearer {bearer}"},
            json={"message": message},
        )
        if r.ok:
            name = r.body.get("name") if isinstance(r.body, dict) else None
            return SendResult(True, "push", "fcm", provider_id=name)
        code = _fcm_error(r)
        if code in ("UNREGISTERED", "NOT_FOUND"):
            return SendResult(False, "push", "dead_token")
        return SendResult(False, "push", f"fcm {r.status} {code}")


def _fcm_error(r: _http.Reply) -> str:
    try:
        return str(r.body["error"]["details"][0]["errorCode"])
    except Exception:
        try:
            return str(r.body["error"]["status"])
        except Exception:
            return r.error or ""


class ApnsTransport:
    def __init__(self) -> None:
        self._jwt = TokenCache()

    def _provider_token(self) -> str | None:
        cached = self._jwt.get()
        if cached:
            return cached
        key = read_text(config.APNS_KEY)
        if not (key and config.APNS_KEY_ID and config.APNS_TEAM_ID):
            return None
        try:
            import jwt  # noqa: PLC0415

            token = jwt.encode(
                {"iss": config.APNS_TEAM_ID, "iat": int(time.time())},
                key,
                algorithm="ES256",
                headers={"kid": config.APNS_KEY_ID},
            )
        except Exception as exc:
            log.warning("could not sign APNs token: %s", type(exc).__name__)
            return None
        # Apple rejects provider tokens older than an hour and wants them
        # refreshed no more often than every 20 minutes.
        return self._jwt.set(token, 50 * 60 + 60)

    def send(self, token: str, title: str, body: str, urgent: bool, payload: dict, kind: str) -> SendResult:
        bearer = self._provider_token()
        if not bearer:
            return SendResult(False, "push", "apns not configured")

        if urgent and config.APNS_CRITICAL_ALERTS:
            level = "critical"
            sound: object = {"critical": 1, "name": "default", "volume": 1.0}
        elif urgent:
            level, sound = "time-sensitive", "default"
        else:
            level, sound = "active", "default"

        aps = {
            "alert": {"title": title, "body": body},
            "sound": sound,
            "interruption-level": level,
            "thread-id": channel_id(kind),
        }
        host = APNS_SANDBOX_HOST if config.APNS_SANDBOX else APNS_HOST
        doc = {"aps": aps, "kind": kind or ""}
        doc.update({str(k): v for k, v in (payload or {}).items()})
        r = _http.request(
            "POST",
            f"{host}/3/device/{token}",
            headers={
                "authorization": f"bearer {bearer}",
                "apns-topic": config.APNS_TOPIC,
                "apns-push-type": "alert",
                "apns-priority": "10" if urgent else "5",
                "apns-expiration": str(int(time.time()) + (3600 if urgent else 86400)),
            },
            json=doc,
            http2=True,
        )
        if r.ok:
            return SendResult(True, "push", f"apns {level}", provider_id=r.headers.get("apns-id"))
        reason = r.body.get("reason", "") if isinstance(r.body, dict) else r.error
        if reason in ("BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic"):
            return SendResult(False, "push", "dead_token")
        return SendResult(False, "push", f"apns {r.status} {reason}")


class PushChannel:
    """The `push` channel. Picks FCM or APNs by the device's platform."""

    name = "push"

    def __init__(self) -> None:
        self.fcm = FcmTransport()
        self.apns = ApnsTransport()

    def send(self, *, to, title, body, push_token=None, payload=None, urgent=False, platform=None, kind="", **kw) -> SendResult:  # noqa: ANN001
        if not push_token:
            return SendResult(False, "push", "no push token")
        transport = self.apns if (platform or "").lower() == "ios" else self.fcm
        return transport.send(push_token, title, body, urgent, payload or {}, kind)
