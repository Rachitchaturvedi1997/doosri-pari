# Doosri Pari API + worker image. One image, two commands:
#   api     gunicorn python.main:app -k uvicorn.workers.UvicornWorker
#   worker  python -m python.jobs           (the scheduler: check-in escalation etc.)
#
# The worker is not optional. Nothing pages a family unless it is running; the API
# will say so on /ready in production if the safety-critical providers are unset,
# and the worker's own log says so at start.

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# tesseract (+ Hindi data) for Sahayak OCR; Noto fonts and Pango for the memory
# book; tzdata because a check-in window is a LOCAL window.
RUN apt-get update && apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-hin tesseract-ocr-eng \
        fonts-noto-core fonts-noto-extra libpango-1.0-0 libpangoft2-1.0-0 \
        tzdata curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt && pip install weasyprint

COPY alembic.ini .
COPY python ./python
COPY shared/contract ./shared/contract

RUN useradd --create-home --uid 10001 app && mkdir -p /app/storage && chown -R app /app
USER app

ENV ENV=production \
    STORAGE_DIR=/app/storage \
    WEB_CONCURRENCY=4

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8000/health || exit 1

CMD ["sh", "-c", "gunicorn python.main:app -k uvicorn.workers.UvicornWorker -w ${WEB_CONCURRENCY} -b 0.0.0.0:8000 --graceful-timeout 30 --timeout 60 --access-logfile -"]
