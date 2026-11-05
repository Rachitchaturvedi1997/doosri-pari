# Doosri Pari

The second-innings app for India's retired government employees and family
pensioners. This repository is the plan's ([`DOOSRI_PARI_PLAN.md`](DOOSRI_PARI_PLAN.md))
backend, shared Kotlin core, Android app and iOS app.

```
python/               FastAPI backend, SQLAlchemy, Alembic, the job scheduler
  features/           one module per area of the plan (283 API operations)
  integrations/       push, SMS, WhatsApp, IVR, Claude, embeddings, storage, Razorpay, ...
  tools/              migrations, seeding, Sahayak ingest, data loaders, API doc
  data/               seed content, the Sahayak source list and evaluation format
shared/               Kotlin Multiplatform: check-in engine, toolkit, models, network, ...
android/app/          Android, Jetpack Compose
ios/                  iOS, native SwiftUI   (never compiled - see ios/README.md)
deploy/               nginx and LiveKit configs
docs/API.md           every endpoint, generated from the code
```

## Where things stand (honestly)

**The backend is feature-complete against the plan and has not been run since it
was written.** It imports, compiles and is clean under pyflakes; that is the whole
of the verification. No test has been executed against the new code, and the
original 50 backend tests have not been re-run since several files they touch were
changed (notably `notify.py`, `escalation.open_today`, `jobs.py`). Testing is the
next phase, on purpose.

| Area | State |
|---|---|
| Check-in, escalation, SOS, guardians | Built end to end: state machine, server-side dispatch on push -> WhatsApp -> SMS -> IVR, the IVR webhooks, retries, "all fine" |
| Background jobs | All 18 written and scheduled (`python -m python.jobs`) |
| Today, Bolo, search, prefs, inbox | Built |
| Saathi (circles, feed, reactions, polls, events, connections, chat, WebSocket) | Built |
| Chai pe Charcha (LiveKit tokens, host controls, schedules) | Built; **recording is deliberately refused (501)** until an egress service exists |
| Seva (listings, matching, bookings, ratings, disputes, Razorpay pay + payouts) | Built |
| Pension Sahayak (hybrid retrieval, Claude answers, volunteer desk, evaluation gate, ingest) | Built; **needs real documents and a 300-question evaluation set** |
| Toolkit, grievance helper, document safe (server side), Seekho, Yatra, Meri Kahani, Association | Built |
| Yog Kendra / Adhyatm Kendra | Built; **programmes load inactive** until a therapist has reviewed them (plan 20) |
| Store billing (Play, App Store) | Built |
| Deploy (Docker, compose, nginx) | Written, not exercised |

### What needs something from you before it does anything real

The code degrades to "not configured" without these, and in production the API
reports **not ready** if the safety-critical ones are missing:

- **Safety-critical:** an FCM service account + APNs key (push), an SMS provider
  with DLT templates (MSG91/Fast2SMS/Twilio), an IVR provider (Twilio/Exotel).
- Claude API key; an embedding model (Voyage key or a self-hosted 1024-dim
  endpoint); S3 bucket; Razorpay + RazorpayX; Play and App Store credentials;
  LiveKit; a speech-to-text provider; a licensed panchang feed.
- **Content only a person can supply:** the Sahayak corpus (official documents, see
  `python/tools/ingest_sahayak.py`), the evaluation set
  (`python/data/sahayak_eval.json`), DR rates and the commutation table (each tied
  to its order), the CGHS/ECHS facility list, radio stations, scripture texts with
  their licences (`python/tools/load_data.py`), and native-speaker review of the
  eight non-Hindi alert translations in `python/messages.py`.

Every variable is in [`.env.example`](.env.example).

### Not built

- Voice-room recording and live captions (plan: recording needs consent flows and
  an egress service; captions are phase 2).
- Printed memoir ordering and teleconsultation (plan phase 3, partner-dependent).
- Sign in with Apple **token revocation on account deletion** (App Store rule
  5.1.1(v)) - needed before the iOS release.
- CI (GitHub Actions / Fastlane), Roborazzi and snapshot tests, Maestro flows.
- The Android navigation shell, onboarding and the wiring of most screens; the iOS
  app has never been compiled. That is the frontend phase.

## Running the backend

```
python -m venv python/.venv
python/.venv/Scripts/pip install -r requirements.txt          # Windows
python/.venv/bin/pip install -r requirements.txt              # macOS / Linux

cp .env.example .env                                          # then edit
python -m python.tools.seed_content                           # prompts, lessons, alerts, ...
uvicorn python.main:app --reload                              # the API   (SQLite, dev)
python -m python.jobs                                         # the scheduler - separate process
```

Nothing pages a family unless the scheduler is running. In production both come
up together:

```
cp .env.example .env
docker compose up -d --build        # db, redis, migrate, api, worker
docker compose --profile voice up -d livekit
```

Put [`deploy/nginx.conf`](deploy/nginx.conf) in front, with `TRUST_PROXY=true`.

## Tools

```
python -m python.jobs --list | --once | --job checkin_escalate
python -m python.tools.seed_content [--only lessons,prompts] [--activate-programmes]
python -m python.tools.ingest_sahayak manifest.json --evaluate
python -m python.tools.load_data facilities|radio|spiritual|dr-rates <file>
python -m python.tools.gen_migration -m "message"
python -m python.tools.gen_api_doc
```

## Tests (when we get to them)

```
python/.venv/Scripts/python -m pytest python/tests -q
JAVA_HOME=<a JDK 21 path> ./gradlew :shared:feature-checkin:jvmTest :android:app:testDebugUnitTest
```

The escalation machine exists three times (Kotlin, Python, Swift). Timings live in
`python/config.py`; `python/tests/test_parity.py` and `test_contract.py` pin the
copies to each other. Change a stage or a delay in one place and they will say so.

## The rules that shape the code

- **The phone keeps the money.** No pension amount, slip or document is ever on the
  server (plan 10, 14). The Today feed carries a DR *rate* and a credit *day*, never
  a rupee figure belonging to a person.
- **A guardian is told only if they accepted and the permission is on.** Withdrawing
  a permission changes what is sent today.
- **Nothing raises into the safety net.** A provider being down is recorded as a
  failed channel; the next one is tried; the job goes on.
- **Cited or nothing.** Sahayak publishes no answer without a source, and a document
  is published only against a *passing* evaluation run.
- **Suggestions, not claims** for religion, health and yoga; a model may suggest
  punctuation or a chapter group but never rewrites a word of someone's story.
- **Independent, not a government app**, said in onboarding, Settings, every
  Sahayak answer and `/app/config`.
