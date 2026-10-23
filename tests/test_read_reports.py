"""Tests focales de scripts/diagnostics/read_reports.py (solo stdlib)."""

from __future__ import annotations

import io
import json
import os
import sys
import urllib.error
from pathlib import Path
from contextlib import redirect_stdout

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts" / "diagnostics"))

import read_reports as rr


ENDPOINT = "https://diagnostico.example.com"
TOKEN = "read-token-secreto"
REPORT_ID = "11111111-2222-4333-8444-555555555555"


class FakeResponse:
    def __init__(self, payload: bytes):
        self._payload = payload
        self._pos = 0

    def read(self, size=-1):
        if size is None or size < 0:
            size = len(self._payload) - self._pos
        chunk = self._payload[self._pos : self._pos + size]
        self._pos += len(chunk)
        return chunk

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class FakeOpener:
    """Opener inyectable: registra peticiones y sirve respuestas fijas."""

    def __init__(self, routes: dict):
        self.routes = routes
        self.requests = []

    def open(self, request, timeout=None):  # noqa: ANN001,ANN202
        url = request.full_url if hasattr(request, "full_url") else request
        self.requests.append((url, dict(request.header_items())))
        for prefix, payload in self.routes.items():
            if url.startswith(prefix):
                if isinstance(payload, Exception):
                    raise payload
                return FakeResponse(payload)
        raise AssertionError(f"URL inesperada: {url}")


@pytest.fixture()
def env(monkeypatch):
    monkeypatch.setenv("DIAGNOSTICS_ENDPOINT", ENDPOINT)
    monkeypatch.setenv("DIAGNOSTICS_READ_TOKEN", TOKEN)
    return monkeypatch


def test_missing_config_fails_closed(capsys, monkeypatch):
    monkeypatch.delenv("DIAGNOSTICS_ENDPOINT", raising=False)
    monkeypatch.delenv("DIAGNOSTICS_READ_TOKEN", raising=False)
    code = rr.main(["list"], opener=FakeOpener({}))
    assert code == 1
    assert "DIAGNOSTICS_ENDPOINT" in capsys.readouterr().err


def test_list_sends_bearer_and_prints_no_secrets(env, capsys):
    body = json.dumps({"items": [{"id": REPORT_ID, "state": "complete"}], "nextCursor": None}).encode()
    opener = FakeOpener({f"{ENDPOINT}/api/reports": body})
    out = io.StringIO()
    with redirect_stdout(out):
        assert rr.main(["list", "--limit", "5"], opener=opener) == 0
    text = out.getvalue()
    assert REPORT_ID in text
    assert TOKEN not in text
    url, headers = opener.requests[0]
    assert url.startswith(f"{ENDPOINT}/api/reports")
    assert headers.get("Authorization") == f"Bearer {TOKEN}"


def test_no_redirects_followed(env):
    opener = FakeOpener({f"{ENDPOINT}/api/reports": urllib.error.HTTPError(
        f"{ENDPOINT}/api/reports", 301, "Moved", {"Location": "https://otro.example/x"}, None)})
    # El manejador real rechaza redirecciones; aquí el opener finge un 301
    # como error para comprobar que main no lo sigue ni lo oculta.
    with pytest.raises(urllib.error.HTTPError):
        opener.open(rr.build_request(f"{ENDPOINT}/api/reports", TOKEN, None))
    handler = rr.NoRedirectHandler()
    with pytest.raises(rr.ReportError, match="redirect rechazado"):
        handler.redirect_request(None, None, 301, "Moved", None, "https://otro.example/x")


def test_attachment_never_prints_signed_url(env, capsys, tmp_path):
    log = "wine ok\n".encode()
    import hashlib

    detail = json.dumps({"attachments": [{"name": "wine", "bytes": len(log), "sha256": hashlib.sha256(log).hexdigest()}]}).encode()
    signed = "https://blob.test/reports/x/wine.txt?firma=secreta"
    routes = {
        f"{ENDPOINT}/api/reports/{REPORT_ID}/attachments": json.dumps({"url": signed}).encode(),
        f"{ENDPOINT}/api/reports/{REPORT_ID}": detail,
        signed: log,
    }
    opener = FakeOpener(routes)
    out = io.StringIO()
    target = tmp_path / "wine.txt"
    with redirect_stdout(out):
        assert rr.main(["attachment", REPORT_ID, "wine", "--out", str(target)], opener=opener) == 0
    text = out.getvalue()
    assert "firma=secreta" not in text
    assert TOKEN not in text
    assert target.read_bytes() == log
    # El token de lectura no viaja a la CDN del adjunto.
    cdn_headers = [h for u, h in opener.requests if u == signed][0]
    assert "Authorization" not in cdn_headers


def test_token_absent_from_errors(env, capsys):
    opener = FakeOpener({ENDPOINT: urllib.error.HTTPError(ENDPOINT, 401, "x", {}, None)})
    code = rr.main(["get", REPORT_ID], opener=opener)
    assert code == 1
    err = capsys.readouterr().err
    assert TOKEN not in err


def test_export_inside_repo_refused(env, tmp_path):
    inside = Path(rr.repo_root()) / "salida.txt"
    with pytest.raises(rr.ReportError, match="dentro del repo"):
        rr.ensure_outside_repo(inside)


def test_attachment_size_cap_enforced(env, tmp_path):
    big = b"x" * 64
    detail = json.dumps({"attachments": []}).encode()
    routes = {
        f"{ENDPOINT}/api/reports/{REPORT_ID}/attachments": json.dumps({"url": "https://blob.test/f"}).encode(),
        f"{ENDPOINT}/api/reports/{REPORT_ID}": detail,
        "https://blob.test/f": big,
    }
    code = rr.main(["attachment", REPORT_ID, "wine", "--out", str(tmp_path / "w.txt"), "--max-bytes", "8"], opener=FakeOpener(routes))
    assert code == 1
    assert not (tmp_path / "w.txt").exists()


def test_invalid_id_rejected(env):
    with pytest.raises(rr.ReportError, match="inválido"):
        rr.check_uuid("no-es-uuid")
    lettered = "11111111-2222-4333-8444-5555555555ab"
    with pytest.raises(rr.ReportError, match="inválido"):
        rr.check_uuid(lettered.upper())
    assert rr.check_uuid(lettered) == lettered


def test_no_token_flag_offered(env):
    parser = rr.build_parser()
    actions = [a.dest for a in parser._actions]
    assert "token" not in actions


def test_existing_part_is_not_reused(env, tmp_path, capsys):
    target = tmp_path / "wine.txt"
    part = tmp_path / "wine.txt.part"
    part.write_bytes(b"restos ajenos")
    code = rr.main(["attachment", REPORT_ID, "wine", "--out", str(target)],
                   opener=FakeOpener({
                       f"{ENDPOINT}/api/reports/{REPORT_ID}/attachments": json.dumps({"url": "https://blob.test/f"}).encode(),
                       f"{ENDPOINT}/api/reports/{REPORT_ID}": json.dumps({"attachments": []}).encode(),
                   }))
    assert code == 1
    assert "temporal existente" in capsys.readouterr().err
    assert part.read_bytes() == b"restos ajenos"
    assert not target.exists()
