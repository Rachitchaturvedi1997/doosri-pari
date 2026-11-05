#!/usr/bin/env bash
# One-command bring-up on a fresh Linux box with Docker installed.
#
#   ./deploy/start.sh
#
# Refuses to start if .env is missing or still has the placeholder secrets, because
# a deployment with the example SECRET_KEY has forgeable login tokens.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "No .env found. Run:  cp .env.example .env   and fill it in." >&2
  exit 1
fi
if grep -q "change-me" .env; then
  echo ".env still contains 'change-me' placeholders (SECRET_KEY / POSTGRES_PASSWORD). Set real values." >&2
  exit 1
fi

docker compose up -d --build
echo "Waiting for the API..."
for i in $(seq 1 40); do
  if curl -fsS http://127.0.0.1:8000/health >/dev/null 2>&1; then
    echo "API is up."
    curl -fsS http://127.0.0.1:8000/ready || true
    echo
    echo "Now: docker compose logs -f worker   # the scheduler - check it says which providers are configured"
    exit 0
  fi
  sleep 3
done
echo "The API did not come up; see: docker compose logs api" >&2
exit 1
