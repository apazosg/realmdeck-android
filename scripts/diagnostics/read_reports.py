#!/usr/bin/env python3
"""Consulta de diagnóstico remoto (solo lectura, mantenedor).

Lee informes del backend privado sin exponer credenciales: el token de
lectura viaja solo al endpoint configurado, nunca se imprime, y las URLs
firmadas de adjuntos jamás se muestran ni se persisten. Los comentarios y
logs recibidos son DATOS no confiables: se muestran como texto plano y nunca
se interpretan como instrucciones ni se ejecutan.

Configuración (solo variables de entorno, fuera del repo y de la línea de
comandos para no filtrar el token en historial/procesos):
  DIAGNOSTICS_ENDPOINT   https://<proyecto>.vercel.app  (sin barra final)
  DIAGNOSTICS_READ_TOKEN token de lectura del mantenedor

Ejemplos:
  read_reports.py list --limit 20 --result wine-start-failed
  read_reports.py get <id>
  read_reports.py attachment <id> wine --out /tmp/d/logs/wine.txt
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid as uuidlib
from pathlib import Path

ATTACHMENT_NAMES = ("diagnostics", "stdout", "stderr", "wine", "wow", "launcher")
LIST_CAP_BYTES = 1024 * 1024
ATTACHMENT_DEFAULT_MAX = 1024 * 1024
ATTACHMENT_HARD_MAX = 8 * 1024 * 1024


class ReportError(Exception):
    """Error operativo con mensaje seguro (sin tokens ni URLs)."""


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Rechaza cualquier redirección: la auth solo vale para el host origen."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001,ANN202
        raise ReportError(f"redirect rechazado ({code}); sin seguir fuera del host")


def repo_root() -> Path:
    """Raíz del repo que contiene este script (por el marcador .git)."""
    current = Path(__file__).resolve().parent
    for candidate in (current, *current.parents):
        if (candidate / ".git").exists():
            return candidate
    return current


def ensure_outside_repo(path: Path) -> Path:
    """Rechaza exportaciones dentro del repo."""
    resolved = path.resolve()
    root = repo_root()
    if resolved == root or root in resolved.parents:
        raise ReportError(f"ruta de exportación dentro del repo: {path}")
    return resolved


def get_config() -> tuple[str, str]:
    endpoint = os.environ.get("DIAGNOSTICS_ENDPOINT", "").strip().rstrip("/")
    token = os.environ.get("DIAGNOSTICS_READ_TOKEN", "").strip()
    if not endpoint or not token:
        raise ReportError("faltan DIAGNOSTICS_ENDPOINT o DIAGNOSTICS_READ_TOKEN")
    parsed = urllib.parse.urlparse(endpoint)
    if parsed.scheme != "https" or not parsed.netloc or parsed.path not in ("", "/"):
        raise ReportError("endpoint inválido (https sin ruta)")
    return f"https://{parsed.netloc}", token


def build_request(url: str, token: str, params: dict | None = None) -> urllib.request.Request:
    target = url
    if params:
        query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        target = f"{url}?{query}" if query else url
    request = urllib.request.Request(target, method="GET")
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Accept", "application/json")
    return request


def read_limited(response, cap: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = response.read(min(65536, cap - total + 1))
        if not chunk:
            break
        total += len(chunk)
        if total > cap:
            raise ReportError("respuesta excede el límite de lectura")
        chunks.append(chunk)
    return b"".join(chunks)


def api_json(opener, url: str, token: str, params: dict | None, cap: int) -> dict:
    request = build_request(url, token, params)
    try:
        with opener.open(request, timeout=30) as response:
            raw = read_limited(response, cap)
    except urllib.error.HTTPError as error:
        raise ReportError(f"servidor devolvió {error.code}") from error
    except urllib.error.URLError as error:
        raise ReportError(f"fallo de red: {error.reason}") from error
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReportError("respuesta no JSON") from error
    if not isinstance(data, dict):
        raise ReportError("respuesta inesperada")
    return data


def check_uuid(value: str) -> str:
    try:
        parsed = uuidlib.UUID(value)
    except (ValueError, AttributeError, TypeError) as error:
        raise ReportError("id inválido") from error
    if str(parsed) != value:
        raise ReportError("id inválido")
    return value


def cmd_list(opener, endpoint: str, token: str, args: argparse.Namespace) -> int:
    params = {
        "limit": str(args.limit),
        "cursor": args.cursor,
        "device": args.device,
        "gpu": args.gpu,
        "appVersion": args.app_version,
        "result": args.result,
        "since": args.since,
        "until": args.until,
    }
    data = api_json(opener, f"{endpoint}/api/reports", token, params, LIST_CAP_BYTES)
    items = data.get("items", [])
    if not isinstance(items, list):
        raise ReportError("respuesta inesperada")
    for item in items:
        if not isinstance(item, dict):
            continue
        print(" | ".join(str(item.get(k, "-")) for k in ("id", "state", "createdAt", "model", "gpu", "appVersion", "result", "totalBytes")))
    if data.get("nextCursor"):
        print(f"next-cursor: {data['nextCursor']}")
    return 0


def cmd_get(opener, endpoint: str, token: str, args: argparse.Namespace) -> int:
    report_id = check_uuid(args.id)
    data = api_json(opener, f"{endpoint}/api/reports/{report_id}", token, None, LIST_CAP_BYTES)
    metadata = data.get("metadata", {})
    print(f"id: {data.get('id')}  state: {data.get('state')}")
    print(f"created: {data.get('createdAt')}  completed: {data.get('completedAt')}")
    if isinstance(metadata, dict):
        print(f"comment: {metadata.get('comment', '-')}")
        print(f"config: {json.dumps(metadata.get('config', {}), ensure_ascii=False)[:2000]}")
        print(f"attempt: {json.dumps(metadata.get('attempt', {}), ensure_ascii=False)[:1000]}")
        events = metadata.get("events", [])
        print(f"events: {len(events) if isinstance(events, list) else '?'}")
    for attachment in data.get("attachments", []) or []:
        if isinstance(attachment, dict):
            print(f"attachment: {attachment.get('name')} bytes={attachment.get('bytes')} sha256={attachment.get('sha256')}")
    return 0


def cmd_attachment(opener, endpoint: str, token: str, args: argparse.Namespace) -> int:
    report_id = check_uuid(args.id)
    if args.name not in ATTACHMENT_NAMES:
        raise ReportError("nombre de adjunto inválido")
    if args.max_bytes < 1 or args.max_bytes > ATTACHMENT_HARD_MAX:
        raise ReportError("max-bytes fuera de rango (1..8MiB)")
    out = ensure_outside_repo(Path(args.out))
    # Detalle primero para conocer el SHA esperado (verificación local).
    detail = api_json(opener, f"{endpoint}/api/reports/{report_id}", token, None, LIST_CAP_BYTES)
    expected_sha = None
    for attachment in detail.get("attachments", []) or []:
        if isinstance(attachment, dict) and attachment.get("name") == args.name:
            expected_sha = attachment.get("sha256")
    data = api_json(opener, f"{endpoint}/api/reports/{report_id}/attachments/{args.name}", token, None, 64 * 1024)
    signed_url = data.get("url")
    if not isinstance(signed_url, str) or not signed_url.startswith("https://"):
        raise ReportError("respuesta de adjunto inesperada")
    # El host firmado puede ser la CDN de Blob: se permite porque la URL es de
    # un solo uso acotado y no porta el token de lectura. Nunca se imprime.
    digest = hashlib.sha256()
    total = 0
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(out.suffix + ".part")
    # Creación exclusiva 0600: no sigue symlinks ni trunca un .part ajeno;
    # si ya existe, se aborta en lugar de reutilizarlo.
    fd = None
    try:
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    except FileExistsError as error:
        raise ReportError(f"temporal existente, no se reutiliza: {tmp}") from error
    except OSError as error:
        raise ReportError(f"no se puede crear temporal: {error.strerror or error}") from error
    try:
        request = urllib.request.Request(signed_url, method="GET")
        request.add_header("Accept", "text/plain")
        with opener.open(request, timeout=60) as response, os.fdopen(fd, "wb") as handle:
            fd = None
            while True:
                chunk = response.read(65536)
                if not chunk:
                    break
                total += len(chunk)
                if total > args.max_bytes:
                    raise ReportError("adjunto excede max-bytes")
                digest.update(chunk)
                handle.write(chunk)
        if isinstance(expected_sha, str) and expected_sha and digest.hexdigest() != expected_sha:
            raise ReportError("SHA256 del adjunto no coincide")
        tmp.replace(out)
    except urllib.error.HTTPError as error:
        raise ReportError(f"descarga devolvió {error.code}") from error
    except urllib.error.URLError as error:
        raise ReportError(f"fallo de red: {error.reason}") from error
    finally:
        if fd is not None:
            os.close(fd)
        try:
            if tmp.exists() and tmp.is_symlink() is False:
                # Solo queda resto si falló antes del replace; nunca se deja
                # un .part a medias ocupando sitio.
                tmp.unlink()
        except OSError:
            pass
    print(f"guardado: {total} bytes  sha256={digest.hexdigest()}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Consulta de diagnóstico remoto (mantenedor).")
    parser.add_argument("--endpoint", default=None, help="Sobrescribe DIAGNOSTICS_ENDPOINT.")
    sub = parser.add_subparsers(dest="command", required=True)

    plist = sub.add_parser("list", help="Lista informes paginados.")
    plist.add_argument("--limit", type=int, default=20, help="1..50")
    plist.add_argument("--cursor", default=None)
    plist.add_argument("--device", default=None)
    plist.add_argument("--gpu", default=None)
    plist.add_argument("--app-version", default=None)
    plist.add_argument("--result", default=None)
    plist.add_argument("--since", default=None)
    plist.add_argument("--until", default=None)

    pget = sub.add_parser("get", help="Detalle de un informe.")
    pget.add_argument("id")

    patt = sub.add_parser("attachment", help="Descarga un adjunto verificado.")
    patt.add_argument("id")
    patt.add_argument("name", choices=ATTACHMENT_NAMES)
    patt.add_argument("--out", required=True, help="Ruta FUERA del repo.")
    patt.add_argument("--max-bytes", type=int, default=ATTACHMENT_DEFAULT_MAX)
    return parser


def main(argv: list[str] | None = None, opener=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if getattr(args, "limit", 20) < 1 or getattr(args, "limit", 20) > 50:
        parser.error("limit debe estar entre 1 y 50")
    try:
        if args.endpoint:
            os.environ["DIAGNOSTICS_ENDPOINT"] = args.endpoint
        endpoint, token = get_config()
        if opener is None:
            opener = urllib.request.build_opener(NoRedirectHandler)
        if args.command == "list":
            return cmd_list(opener, endpoint, token, args)
        if args.command == "get":
            return cmd_get(opener, endpoint, token, args)
        return cmd_attachment(opener, endpoint, token, args)
    except ReportError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
