# Every route, once, as a signed-in member and as an admin.
#
# Not a behaviour test. It walks the OpenAPI document, builds the smallest body
# each schema accepts, and fails on any 5xx. A 4xx is fine - "not found", "not
# allowed", "check the form" are all answers. A 500 is a crash a pensioner would
# see as "something went wrong", and this is the net that catches the ones no
# behaviour test was written for.
from __future__ import annotations

import re

import pytest

from python.main import app

# Routes that reach a network or a process that is not here, by design.
_SKIP = {
    ("GET", "/ws"),
}


def _resolve(schema: dict, spec: dict) -> dict:
    while "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        schema = spec["components"]["schemas"][name]
    return schema


def _example(schema: dict, spec: dict, depth: int = 0):
    schema = _resolve(schema, spec)
    if depth > 6:
        return None
    for key in ("anyOf", "oneOf"):
        if key in schema:
            options = [s for s in schema[key] if _resolve(s, spec).get("type") != "null"]
            return _example(options[0] if options else schema[key][0], spec, depth + 1)
    if "allOf" in schema:
        return _example(schema["allOf"][0], spec, depth + 1)
    if "default" in schema:
        return schema["default"]
    if "enum" in schema:
        return schema["enum"][0]
    if "const" in schema:
        return schema["const"]
    t = schema.get("type")
    if t == "object" or "properties" in schema:
        props = schema.get("properties", {})
        required = schema.get("required", [])
        return {k: _example(v, spec, depth + 1) for k, v in props.items() if k in required}
    if t == "array":
        n = max(schema.get("minItems", 0), 1)
        return [_example(schema.get("items", {}), spec, depth + 1) for _ in range(n)]
    if t == "integer":
        return max(schema.get("minimum", 1), schema.get("exclusiveMinimum", 0) + 1)
    if t == "number":
        return float(schema.get("minimum", 1))
    if t == "boolean":
        return True
    if t == "string":
        fmt = schema.get("format")
        if fmt == "email":
            return "a@example.com"
        if fmt == "date":
            return "2026-01-01"
        if fmt == "date-time":
            return "2026-01-01T09:00:00Z"
        pat = schema.get("pattern", "")
        if "0-9" in pat and ":" in pat:
            return "09:00"
        if pat.startswith("^\\+") or "phone" in pat:
            return "+919811111111"
        n = max(schema.get("minLength", 1), 1)
        return ("x" * n)[: schema.get("maxLength", n)]
    return None


def _operations():
    spec = app.openapi()
    for path, item in spec["paths"].items():
        for method, op in item.items():
            if method.upper() not in {"GET", "POST", "PUT", "PATCH", "DELETE"}:
                continue
            if (method.upper(), path) in _SKIP:
                continue
            yield method.upper(), path, op, spec


_OPS = sorted({(m, p) for m, p, _, _ in _operations()})


def _build(method: str, path: str):
    spec = app.openapi()
    op = spec["paths"][path][method.lower()]
    url = re.sub(r"\{[^}]+\}", "1", path)
    params = {}
    for prm in op.get("parameters", []):
        if prm.get("in") == "query" and prm.get("required"):
            params[prm["name"]] = _example(prm.get("schema", {}), spec)
    body = None
    files = None
    content = op.get("requestBody", {}).get("content", {})
    if "application/json" in content:
        body = _example(content["application/json"]["schema"], spec)
    elif "multipart/form-data" in content:
        schema = _resolve(content["multipart/form-data"]["schema"], spec)
        files = {}
        data = {}
        for k, v in schema.get("properties", {}).items():
            v = _resolve(v, spec)
            if v.get("format") == "binary" or v.get("contentMediaType"):
                files[k] = ("a.jpg", b"\xff\xd8\xff\xe0" + b"0" * 64, "image/jpeg")
            elif k in schema.get("required", []):
                data[k] = str(_example(v, spec))
        return url, params, None, files, data
    return url, params, body, files, None


@pytest.mark.parametrize("method,path", _OPS, ids=[f"{m} {p}" for m, p in _OPS])
@pytest.mark.parametrize("who", ["member", "admin"])
def test_route_does_not_crash(client, auth, admin_auth, who, method, path):
    headers = auth if who == "member" else admin_auth
    url, params, body, files, data = _build(method, path)
    kwargs = {"headers": headers, "params": params}
    if files is not None:
        kwargs["files"] = files
        kwargs["data"] = data
    elif body is not None:
        kwargs["json"] = body
    r = client.request(method, url, **kwargs)
    # 501 and 503 are deliberate: "not built" and "provider not configured".
    assert r.status_code not in (500, 502), f"{method} {url} -> {r.status_code}: {r.text[:300]}"
