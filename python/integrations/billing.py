# =============================================================================
# Store billing: Google Play and the App Store. Plan sections 9 and 17.
#
# The store is the source of truth about payment. Everything here is the
# cross-examination the seams in python/features/subscriptions.py ask for: a
# receipt from the phone is a claim, and these functions are the only place it
# is believed.
#
#   Play       purchases.subscriptionsv2.get with a service-account token, then
#              acknowledge (an unacknowledged purchase is refunded by Google
#              after three days — a paying family silently losing their plan).
#   App Store  the App Store Server API with an ES256 provider JWT, and every
#              signed payload (transaction info, server notification) verified
#              up its x5c chain to Apple's root before a byte of it is trusted.
#
# Client contract for attributing a purchase to a payer:
#   Play       obfuscatedExternalAccountId = str(user_id)
#   App Store  appAccountToken = uuid.UUID(int=user_id)
# =============================================================================
from __future__ import annotations

import base64
import logging
import time
import uuid
from datetime import datetime

from python import config
from python.integrations import _google, _http
from python.integrations._secrets import TokenCache, read_text

log = logging.getLogger("doosri.billing")

PLAY_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
PLAY_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"

_PLAY_STATE = {
    "SUBSCRIPTION_STATE_ACTIVE": "active",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD": "grace_period",
    "SUBSCRIPTION_STATE_CANCELED": "cancelled",
    "SUBSCRIPTION_STATE_PAUSED": "cancelled",
    "SUBSCRIPTION_STATE_ON_HOLD": "expired",
    "SUBSCRIPTION_STATE_EXPIRED": "expired",
    "SUBSCRIPTION_STATE_PENDING": "none",
    "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED": "none",
}
# App Store Server API subscription status codes.
_APPLE_STATUS = {1: "active", 2: "expired", 3: "grace_period", 4: "grace_period", 5: "refunded"}


def _rfc3339(value: str | None) -> float | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def _payer_from_play(account_id: str | None) -> int | None:
    try:
        return int(account_id) if account_id else None
    except ValueError:
        return None


def _payer_from_apple(token: str | None) -> int | None:
    try:
        n = uuid.UUID(token).int if token else None
        return n if n is not None and n < 2**32 else None
    except ValueError:
        return None


# ---------------------------------------------------------------------- play --
def verify_play(purchase_token: str) -> dict | None:
    """The normalised state of a Play subscription, or None if it cannot be verified."""
    if not config.GOOGLE_PLAY_SERVICE_ACCOUNT:
        return None
    bearer = _google.access_token(config.GOOGLE_PLAY_SERVICE_ACCOUNT, PLAY_SCOPE)
    if not bearer:
        return None
    r = _http.request(
        "GET",
        f"{PLAY_BASE}/{config.GOOGLE_PLAY_PACKAGE}/purchases/subscriptionsv2/tokens/{purchase_token}",
        headers={"Authorization": f"Bearer {bearer}"},
    )
    if not r.ok or not isinstance(r.body, dict):
        log.warning("play verification failed: status=%s", r.status)
        return None
    body = r.body
    items = body.get("lineItems") or [{}]
    item = items[0]
    status = _PLAY_STATE.get(body.get("subscriptionState", ""), "none")
    expiry = _rfc3339(item.get("expiryTime"))
    auto_renew = (item.get("autoRenewingPlan") or {}).get("autoRenewEnabled")

    if body.get("acknowledgementState") == "ACKNOWLEDGEMENT_STATE_PENDING" and status in ("active", "grace_period"):
        _acknowledge_play(bearer, item.get("productId", ""), purchase_token)

    return {
        "status": status,
        "product_id": item.get("productId", ""),
        "renews_at": expiry if auto_renew else None,
        "valid_until": expiry,
        "store_transaction_id": body.get("latestOrderId"),
        "receipt_ref": purchase_token[:255],
        "payer_id": _payer_from_play(
            (body.get("externalAccountIdentifiers") or {}).get("obfuscatedExternalAccountId")
        ),
    }


def _acknowledge_play(bearer: str, product_id: str, token: str) -> None:
    if not product_id:
        return
    r = _http.request(
        "POST",
        f"{PLAY_BASE}/{config.GOOGLE_PLAY_PACKAGE}/purchases/subscriptions/{product_id}/tokens/{token}:acknowledge",
        headers={"Authorization": f"Bearer {bearer}"},
        json={},
    )
    if not r.ok:
        log.warning("play acknowledge failed: status=%s", r.status)


# --------------------------------------------------------------- app store ---
_apple_jwt = TokenCache()


def _apple_provider_token() -> str | None:
    cached = _apple_jwt.get()
    if cached:
        return cached
    key = read_text(config.APPSTORE_KEY)
    if not (key and config.APPSTORE_KEY_ID and config.APPSTORE_ISSUER_ID):
        return None
    try:
        import jwt  # noqa: PLC0415

        now = int(time.time())
        token = jwt.encode(
            {
                "iss": config.APPSTORE_ISSUER_ID,
                "iat": now,
                "exp": now + 1500,
                "aud": "appstoreconnect-v1",
                "bid": config.APPSTORE_BUNDLE_ID,
            },
            key,
            algorithm="ES256",
            headers={"kid": config.APPSTORE_KEY_ID},
        )
    except Exception as exc:
        log.warning("could not sign App Store token: %s", type(exc).__name__)
        return None
    return _apple_jwt.set(token, 1400)


def verify_jws(signed: str) -> dict | None:
    """
    Verify an Apple-signed JWS and return its payload, or None.

    Steps, in the order Apple documents:
      1. read the x5c certificate chain from the header;
      2. confirm each certificate is signed by the next, and the last one is (or
         is signed by) Apple Root CA G3, supplied by the operator in
         APPSTORE_ROOT_CERT — this repository does not ship a trust anchor it
         cannot itself verify;
      3. check validity dates;
      4. verify the ES256 signature with the leaf's public key.
    An unverified JWS is a stranger's JSON: any failure returns None.
    """
    root_pem = read_text(config.APPSTORE_ROOT_CERT)
    if not root_pem:
        log.warning("APPSTORE_ROOT_CERT is not set; refusing to trust an Apple JWS")
        return None
    try:
        import jwt  # noqa: PLC0415
        from cryptography import x509  # noqa: PLC0415
        from cryptography.hazmat.primitives import serialization  # noqa: PLC0415
        from cryptography.hazmat.primitives.asymmetric import ec  # noqa: PLC0415

        header = jwt.get_unverified_header(signed)
        if header.get("alg") != "ES256":
            return None
        chain = [
            x509.load_der_x509_certificate(base64.b64decode(c)) for c in header.get("x5c", [])
        ]
        if len(chain) < 2:
            return None
        root = x509.load_pem_x509_certificate(root_pem.encode())

        now = datetime.utcnow()
        for cert in chain:
            if not (cert.not_valid_before <= now <= cert.not_valid_after):
                return None

        def signed_by(child, parent) -> bool:  # noqa: ANN001
            try:
                parent.public_key().verify(
                    child.signature,
                    child.tbs_certificate_bytes,
                    ec.ECDSA(child.signature_hash_algorithm),
                )
                return True
            except Exception:
                return False

        for child, parent in zip(chain, chain[1:]):
            if not signed_by(child, parent):
                return None
        top = chain[-1]
        same_root = top.fingerprint(top.signature_hash_algorithm) == root.fingerprint(
            top.signature_hash_algorithm
        )
        if not (same_root or signed_by(top, root)):
            return None

        leaf_key = chain[0].public_key()
        pem = leaf_key.public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        return jwt.decode(signed, pem, algorithms=["ES256"], options={"verify_aud": False})
    except Exception as exc:
        log.warning("apple jws rejected: %s", type(exc).__name__)
        return None


def _apple_state(txn: dict, status_code: int | None) -> dict:
    expires = txn.get("expiresDate")
    revoked = txn.get("revocationDate")
    status = _APPLE_STATUS.get(status_code or 0)
    if status is None:
        status = "refunded" if revoked else ("active" if (expires or 0) / 1000 > time.time() else "expired")
    return {
        "status": status,
        "product_id": txn.get("productId", ""),
        "renews_at": (expires / 1000.0) if expires and status == "active" else None,
        "valid_until": (expires / 1000.0) if expires else None,
        "store_transaction_id": str(txn.get("originalTransactionId") or txn.get("transactionId") or "") or None,
        "receipt_ref": str(txn.get("transactionId") or "")[:255] or None,
        "payer_id": _payer_from_apple(txn.get("appAccountToken")),
    }


def verify_app_store(transaction: str) -> dict | None:
    """
    `transaction` is a transaction id, or the signed transaction JWS StoreKit 2
    gives the app. Either way the answer comes from Apple's API, not the client.
    """
    if transaction.count(".") == 2:
        payload = verify_jws(transaction)
        if payload is None:
            return None
        transaction = str(payload.get("originalTransactionId") or payload.get("transactionId") or "")
    if not transaction:
        return None
    bearer = _apple_provider_token()
    if not bearer:
        return None
    host = "api.storekit-sandbox.itunes.apple.com" if config.APPSTORE_SANDBOX else "api.storekit.itunes.apple.com"
    r = _http.request(
        "GET",
        f"https://{host}/inApps/v1/subscriptions/{transaction}",
        headers={"Authorization": f"Bearer {bearer}"},
    )
    if not r.ok or not isinstance(r.body, dict):
        log.warning("app store lookup failed: status=%s", r.status)
        return None
    for group in r.body.get("data", []):
        for last in group.get("lastTransactions", []):
            txn = verify_jws(last.get("signedTransactionInfo", ""))
            if txn is None:
                continue
            return _apple_state(txn, last.get("status"))
    return None


def verify_apple_notification(signed_payload: str) -> dict | None:
    """Decode an App Store Server Notification V2 (see subscriptions.verify_apple_jws)."""
    payload = verify_jws(signed_payload)
    if payload is None:
        return None
    data = payload.get("data") or {}
    state = None
    signed_txn = data.get("signedTransactionInfo")
    if signed_txn:
        txn = verify_jws(signed_txn)
        if txn is not None:
            ntype = payload.get("notificationType", "")
            code = {"EXPIRED": 2, "GRACE_PERIOD_EXPIRED": 2, "REFUND": 5, "REVOKE": 5}.get(ntype)
            state = _apple_state(txn, code)
    return {
        "event_id": payload.get("notificationUUID", ""),
        "event_type": payload.get("notificationType", ""),
        "environment": data.get("environment") or "Production",
        "state": state,
    }


def fetch_state(store: str, receipt_ref: str) -> dict | None:
    """One entry point for the hourly reconciliation job."""
    if store == "play":
        return verify_play(receipt_ref)
    if store == "app_store":
        return verify_app_store(receipt_ref)
    return None
