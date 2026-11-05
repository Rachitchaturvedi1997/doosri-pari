# =============================================================================
# Wiring: which providers are live in this process.
#
# Called once at startup by python.main and by python.jobs. It installs the real
# channels into the process-wide dispatcher and logs, in one place, which
# integrations are configured — because "the SMS vendor was never set" should be
# something an operator reads at boot, not something a family discovers at 3am.
# =============================================================================
from __future__ import annotations

import logging

from python import config
from python.integrations.ivr import IvrChannel
from python.integrations.push import PushChannel
from python.integrations.sms import SmsChannel
from python.integrations.whatsapp import WhatsAppChannel
from python.notify import dispatch

log = logging.getLogger("doosri.integrations")


def status() -> dict[str, bool]:
    """Which integrations have enough configuration to try."""
    return {
        "push_fcm": bool(config.FCM_PROJECT_ID and config.FCM_SERVICE_ACCOUNT),
        "push_apns": bool(config.APNS_KEY and config.APNS_KEY_ID and config.APNS_TEAM_ID),
        "sms": bool(
            (config.SMS_PROVIDER == "msg91" and config.MSG91_AUTH_KEY)
            or (config.SMS_PROVIDER == "fast2sms" and config.FAST2SMS_API_KEY)
            or (config.SMS_PROVIDER == "twilio" and config.TWILIO_ACCOUNT_SID)
        ),
        "whatsapp": bool(config.WHATSAPP_TOKEN and config.WHATSAPP_PHONE_ID),
        "ivr": bool(
            (config.IVR_PROVIDER == "twilio" and config.TWILIO_ACCOUNT_SID)
            or (config.IVR_PROVIDER == "exotel" and config.EXOTEL_SID)
        ),
        "llm": bool(config.ANTHROPIC_API_KEY),
        "embeddings": bool(config.VOYAGE_API_KEY or config.EMBEDDINGS_URL),
        "speech_to_text": bool(config.STT_PROVIDER),
        "object_storage": bool(config.S3_BUCKET),
        "razorpay": bool(config.RAZORPAY_KEY_ID and config.RAZORPAY_KEY_SECRET),
        "google_play": bool(config.GOOGLE_PLAY_SERVICE_ACCOUNT),
        "app_store": bool(config.APPSTORE_KEY and config.APPSTORE_KEY_ID),
        "livekit": bool(config.LIVEKIT_URL and config.LIVEKIT_API_KEY),
        "panchang": bool(config.PANCHANG_URL),
    }


# Without these, a missed check-in reaches nobody. They are called out loudly.
SAFETY_CRITICAL = ("push_fcm", "sms", "ivr")


def configure_dispatch() -> dict[str, bool]:
    """Install the real channels. Safe to call more than once."""
    if config.IS_TEST:
        # Tests inject their own fake channels (notify.set_dispatch). Replacing
        # them at app startup would make every alert test depend on a vendor.
        return status()
    strict = config.ENV == "production"
    st = status()
    # A channel with no provider is left OUT, so notify's NullChannel stands in for
    # it: in development that logs and reports success (a developer is not told
    # their notification failed because they have no SMS account), and in
    # production (`strict`) it reports failure honestly, which is what an
    # operator needs to see.
    live = []
    if st["push_fcm"] or st["push_apns"]:
        live.append(PushChannel())
    if st["whatsapp"]:
        live.append(WhatsAppChannel())
    if st["sms"]:
        live.append(SmsChannel())
    if st["ivr"]:
        live.append(IvrChannel())
    dispatch.configure(live, strict=strict)
    configured = ", ".join(k for k, v in st.items() if v) or "none"
    log.info("integrations configured: %s", configured)
    if config.ENV == "production":
        for key in SAFETY_CRITICAL:
            if not st[key]:
                log.error(
                    "SAFETY: %s is not configured; missed check-ins may reach nobody", key
                )
    return st
