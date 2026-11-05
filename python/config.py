# =============================================================================
# Doosri Pari — configuration.
#
# Every setting is an environment variable with a safe development default, so
# a fresh clone runs with nothing but Python installed. Production values come
# from AWS Mumbai (plan section 9) and a secrets manager.
# =============================================================================
import os
from pathlib import Path

from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(PROJECT_ROOT / ".env")

ENV = os.getenv("ENV", "development")
IS_TEST = ENV == "test"

# Default to a throw-away SQLite file; Postgres 16 + pgvector in production.
DATABASE_URL = os.getenv("DATABASE_URL", f"sqlite:///{PROJECT_ROOT / 'app.db'}")

SECRET_KEY = os.getenv("SECRET_KEY", "dev-secret-do-not-use-in-production")
JWT_ALGO = "HS256"
ACCESS_TTL = int(os.getenv("ACCESS_TTL", "3600"))          # seconds
REFRESH_TTL = int(os.getenv("REFRESH_TTL", str(60 * 60 * 24 * 30)))

# Plan section 21 risk 1. If this is left at the default in production, every
# token in the deployment is forgeable by anyone who has read this file, and
# the signing key is in a public repository. Refuse to start rather than serve.
if ENV not in ("development", "test") and SECRET_KEY == "dev-secret-do-not-use-in-production":
    raise RuntimeError(
        "SECRET_KEY is still the development default while ENV="
        f"{ENV!r}. Set a real one; see plan section 9 (secrets manager)."
    )

# Only true when a proxy in front of this app sets X-Forwarded-For. The header
# is client-supplied, so trusting it without a proxy lets any caller pick their
# own rate-limit identity by sending a different one each time.
TRUST_PROXY = os.getenv("TRUST_PROXY", "").lower() in ("1", "true", "yes")

# The dev-only header auth. Off unless explicitly switched on, because this is
# not a missing feature: a build with it on lets anyone who guesses a user id
# read another pensioner's check-ins and close their alerts. See python/deps.py.
ALLOW_DEV_HEADER_AUTH = os.getenv("ALLOW_DEV_HEADER_AUTH", "").lower() in (
    "1",
    "true",
    "yes",
)
if ALLOW_DEV_HEADER_AUTH and ENV not in ("development", "test"):
    raise RuntimeError(
        "ALLOW_DEV_HEADER_AUTH is on outside development. That header is not "
        "authentication; shipping it would expose every account to id guessing."
    )

REDIS_URL = os.getenv("REDIS_URL", "")

# Capacity, per API process. Sync routes run in a thread pool and each thread
# holds at most one pooled connection, so the pool is sized to the threads: a
# thread that waits for a connection is a request that waits for nothing.
# Total Postgres connections = processes x (pool + overflow); in production
# they go through PgBouncer (docker-compose.yml), not straight to Postgres.
THREADPOOL_SIZE = int(os.getenv("THREADPOOL_SIZE", "64"))
DB_POOL_SIZE = int(os.getenv("DB_POOL_SIZE", "40"))
DB_MAX_OVERFLOW = int(os.getenv("DB_MAX_OVERFLOW", "24"))
DB_POOL_TIMEOUT = int(os.getenv("DB_POOL_TIMEOUT", "10"))
# Threads the scheduler uses to advance check-ins in parallel. Most windows
# open at 09:00, so most reminders fall due in the same minute; one at a time,
# a few thousand provider calls would take longer than the minute they are for.
JOB_CONCURRENCY = int(os.getenv("JOB_CONCURRENCY", "16"))

# Escalation timings mirror shared/feature-checkin CheckInSettings. They live
# here too so an operator can tune a rollout without shipping an app build;
# the shared module stays the source of truth for the shipped behaviour.
REMIND_AFTER_S = int(os.getenv("REMIND_AFTER_S", "3600"))          # window start + 1 h
IVR_RETRY_GAP_S = int(os.getenv("IVR_RETRY_GAP_S", "600"))         # 10 min between calls
IVR_ATTEMPTS = int(os.getenv("IVR_ATTEMPTS", "2"))
HELPER_ESCALATION_S = int(os.getenv("HELPER_ESCALATION_S", "3600"))  # 60 min
PAUSE_MIN_DAYS = 1
PAUSE_MAX_DAYS = 30

MAX_GUARDIANS = 5
MAX_HELPERS = 3

LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")

# Plan section 11: writes are rate limited per user and per IP.
RATE_LIMIT_OTP = 5
RATE_LIMIT_WRITE = 60

# Plan section 14: retention.
RETENTION_CHECKIN_DAYS = 365
RETENTION_SOS_DAYS = 365


# =============================================================================
# Integrations. Every provider is optional: with nothing set, the app runs in
# development with logging channels and the jobs report "not configured"
# instead of failing. In production `python/integrations/status.py` prints which
# of these are missing at boot, because a check-in alert with no SMS provider is
# the failure this whole app exists to prevent.
# =============================================================================
def _flag(name: str, default: bool = False) -> bool:
    return os.getenv(name, str(default)).lower() in ("1", "true", "yes")


# Run `alembic upgrade head` when the API boots. Off in tests; on in the
# container so a fresh deploy gets a schema without a manual step.
RUN_MIGRATIONS_ON_START = _flag("RUN_MIGRATIONS_ON_START", False)

# --- push -------------------------------------------------------------------
# FCM HTTP v1. The service-account JSON is a path or the raw JSON.
FCM_PROJECT_ID = os.getenv("FCM_PROJECT_ID", "")
FCM_SERVICE_ACCOUNT = os.getenv("FCM_SERVICE_ACCOUNT", "")
# APNs token auth (.p8). Topic is the app's bundle id.
APNS_KEY_ID = os.getenv("APNS_KEY_ID", "")
APNS_TEAM_ID = os.getenv("APNS_TEAM_ID", "")
APNS_KEY = os.getenv("APNS_KEY", "")            # path to the .p8, or its contents
APNS_TOPIC = os.getenv("APNS_TOPIC", "in.doosripari.app")
APNS_SANDBOX = _flag("APNS_SANDBOX", False)
# Only true once Apple has granted the Critical Alerts entitlement (plan 8.3).
APNS_CRITICAL_ALERTS = _flag("APNS_CRITICAL_ALERTS", False)

# --- SMS (DLT-registered templates, plan 9) -----------------------------------
SMS_PROVIDER = os.getenv("SMS_PROVIDER", "")     # msg91 | fast2sms | twilio | ""
MSG91_AUTH_KEY = os.getenv("MSG91_AUTH_KEY", "")
MSG91_SENDER_ID = os.getenv("MSG91_SENDER_ID", "DPARI")
# One DLT template id per message kind. Unset kinds fall back to "generic".
MSG91_TEMPLATES = {
    k: os.getenv(f"MSG91_TEMPLATE_{k.upper()}", "")
    for k in ("otp", "generic", "guardian_alert", "sos", "all_clear", "invite")
}
FAST2SMS_API_KEY = os.getenv("FAST2SMS_API_KEY", "")
TWILIO_ACCOUNT_SID = os.getenv("TWILIO_ACCOUNT_SID", "")
TWILIO_AUTH_TOKEN = os.getenv("TWILIO_AUTH_TOKEN", "")
TWILIO_FROM = os.getenv("TWILIO_FROM", "")

# --- WhatsApp Business (Cloud API templates) ----------------------------------
WHATSAPP_TOKEN = os.getenv("WHATSAPP_TOKEN", "")
WHATSAPP_PHONE_ID = os.getenv("WHATSAPP_PHONE_ID", "")
WHATSAPP_TEMPLATE_ALERT = os.getenv("WHATSAPP_TEMPLATE_ALERT", "doosri_alert")
WHATSAPP_TEMPLATE_ALL_CLEAR = os.getenv("WHATSAPP_TEMPLATE_ALL_CLEAR", "doosri_all_clear")

# --- IVR (automated check-in call) --------------------------------------------
IVR_PROVIDER = os.getenv("IVR_PROVIDER", "")     # twilio | exotel | ""
EXOTEL_SID = os.getenv("EXOTEL_SID", "")
EXOTEL_API_KEY = os.getenv("EXOTEL_API_KEY", "")
EXOTEL_API_TOKEN = os.getenv("EXOTEL_API_TOKEN", "")
EXOTEL_SUBDOMAIN = os.getenv("EXOTEL_SUBDOMAIN", "api.exotel.com")
EXOTEL_CALLER_ID = os.getenv("EXOTEL_CALLER_ID", "")
EXOTEL_FLOW_URL = os.getenv("EXOTEL_FLOW_URL", "")
# Where the vendor reaches us. Must be HTTPS and public.
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "http://localhost:8000")
IVR_TOKEN_TTL_S = int(os.getenv("IVR_TOKEN_TTL_S", "7200"))

# --- language models ----------------------------------------------------------
ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "") or os.getenv("SAHAYAK_LLM_API_KEY", "")
LLM_MODEL_FAST = os.getenv("LLM_MODEL_FAST", "claude-haiku-4-5")
LLM_MODEL_STRONG = os.getenv("LLM_MODEL_STRONG", "claude-sonnet-5-5")
LLM_TIMEOUT_S = float(os.getenv("LLM_TIMEOUT_S", "30"))

# --- embeddings (must be 1024 dims; the column is vector(1024)) ---------------
VOYAGE_API_KEY = os.getenv("VOYAGE_API_KEY", "")
VOYAGE_MODEL = os.getenv("VOYAGE_MODEL", "voyage-3")
# Or any OpenAI-compatible /embeddings endpoint, e.g. a self-hosted TEI server.
EMBEDDINGS_URL = os.getenv("EMBEDDINGS_URL", "")
EMBEDDINGS_KEY = os.getenv("EMBEDDINGS_KEY", "")
EMBEDDINGS_MODEL = os.getenv("EMBEDDINGS_MODEL", "bge-m3")

# --- speech -------------------------------------------------------------------
STT_PROVIDER = os.getenv("STT_PROVIDER", "")     # whisper | google | ""
STT_URL = os.getenv("STT_URL", "https://api.openai.com/v1/audio/transcriptions")
STT_KEY = os.getenv("STT_KEY", "")
STT_MODEL = os.getenv("STT_MODEL", "whisper-1")
GOOGLE_SPEECH_API_KEY = os.getenv("GOOGLE_SPEECH_API_KEY", "")

# --- object storage -----------------------------------------------------------
S3_BUCKET = os.getenv("S3_BUCKET", "")
S3_REGION = os.getenv("S3_REGION", "ap-south-1")
S3_ENDPOINT = os.getenv("S3_ENDPOINT", "")       # MinIO / R2 / localstack
# Development fallback so uploads work with no cloud account.
STORAGE_DIR = os.getenv("STORAGE_DIR", str(PROJECT_ROOT / "storage"))
UPLOAD_URL_TTL_S = int(os.getenv("UPLOAD_URL_TTL_S", "900"))
UPLOAD_MAX_BYTES = int(os.getenv("UPLOAD_MAX_BYTES", str(25 * 1024 * 1024)))

# --- payments -----------------------------------------------------------------
RAZORPAY_KEY_ID = os.getenv("RAZORPAY_KEY_ID", "")
RAZORPAY_KEY_SECRET = os.getenv("RAZORPAY_KEY_SECRET", "")
RAZORPAY_WEBHOOK_SECRET = os.getenv("RAZORPAY_WEBHOOK_SECRET", "")
GOOGLE_PLAY_PACKAGE = os.getenv("GOOGLE_PLAY_PACKAGE", "in.doosripari.app")
GOOGLE_PLAY_SERVICE_ACCOUNT = os.getenv("GOOGLE_PLAY_SERVICE_ACCOUNT", "")
APPSTORE_KEY_ID = os.getenv("APPSTORE_KEY_ID", "")
APPSTORE_ISSUER_ID = os.getenv("APPSTORE_ISSUER_ID", "")
APPSTORE_KEY = os.getenv("APPSTORE_KEY", "")
APPSTORE_BUNDLE_ID = os.getenv("APPSTORE_BUNDLE_ID", "in.doosripari.app")
APPSTORE_SANDBOX = _flag("APPSTORE_SANDBOX", False)

# --- voice rooms --------------------------------------------------------------
LIVEKIT_URL = os.getenv("LIVEKIT_URL", "")
LIVEKIT_API_KEY = os.getenv("LIVEKIT_API_KEY", "")
LIVEKIT_API_SECRET = os.getenv("LIVEKIT_API_SECRET", "")

# --- data feeds ---------------------------------------------------------------
PANCHANG_URL = os.getenv("PANCHANG_URL", "")
PANCHANG_KEY = os.getenv("PANCHANG_KEY", "")
PANCHANG_DIR = os.getenv("PANCHANG_DIR", str(PROJECT_ROOT / "data" / "panchang"))
CRAWL_USER_AGENT = os.getenv(
    "CRAWL_USER_AGENT", "DoosriPariBot/1.0 (+https://doosripari.in/bot; independent, not a government app)"
)

# --- app store links / misc ---------------------------------------------------
# Escalation window for the server-side notification quotas (plan 6.15).
MAX_NON_URGENT_PER_DAY = int(os.getenv("MAX_NON_URGENT_PER_DAY", "4"))
QUIET_HOURS = (21, 7)
SUPPORT_EMAIL = os.getenv("SUPPORT_EMAIL", "support@doosripari.in")
GRIEVANCE_OFFICER = os.getenv("GRIEVANCE_OFFICER", "Grievance Officer, Doosri Pari")

# --- additions ----------------------------------------------------------------
RAZORPAYX_ACCOUNT_NUMBER = os.getenv("RAZORPAYX_ACCOUNT_NUMBER", "")
# Apple Root CA G3, supplied by the operator (PEM contents or a path). Without it
# no App Store JWS is trusted.
APPSTORE_ROOT_CERT = os.getenv("APPSTORE_ROOT_CERT", "")
GOOGLE_PUBSUB_VERIFY_TOKEN = os.getenv("GOOGLE_PUBSUB_VERIFY_TOKEN", "")
