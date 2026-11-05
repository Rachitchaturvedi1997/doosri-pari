# =============================================================================
# Integrations — the code that talks to the outside world.
#
# One module per vendor concern. Each has the same three properties:
#
#   1. It imports no vendor SDK at module scope. httpx, boto3, anthropic,
#      pdfplumber are all optional dependencies imported inside the function
#      that needs them, so `import python.main` works on a laptop with nothing
#      installed and a missing dependency turns one feature into a logged
#      "not configured" instead of taking the API down.
#   2. It reads its credentials from python/config.py and nowhere else.
#   3. It never raises for a provider being down. It returns a result the caller
#      can inspect, because the callers are safety-critical (a check-in alert)
#      and "the SMS vendor is down" is an expected event, not an exception.
# =============================================================================
