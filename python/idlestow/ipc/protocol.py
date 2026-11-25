"""Message construction and request validation."""

from __future__ import annotations

import json
import os
import re
import sys
from collections.abc import Collection
from dataclasses import dataclass
from typing import Any

from .. import PROTOCOL_VERSION, WORKER_NAME, __version__, _optional
from ..errors import Code, WorkerError

_ID_RE = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")


@dataclass(frozen=True)
class Request:
    id: str
    op: str
    params: dict[str, Any]


class RequestError(Exception):
    """A request that must be answered with an error; ``id`` is None if it could not be determined."""

    def __init__(self, request_id: str | None, error: WorkerError) -> None:
        super().__init__(str(error))
        self.request_id = request_id
        self.error = error


def capabilities() -> dict[str, Any]:
    zstd = _optional.zstd_present()
    return {
        "formats": ["zip", "tar.zst"] if zstd else ["zip"],
        "zstd": zstd,
        "psutil": _optional.psutil_present(),
    }


def ready_message() -> dict[str, Any]:
    return {
        "v": PROTOCOL_VERSION,
        "type": "ready",
        "worker": WORKER_NAME,
        "version": __version__,
        "protocol": PROTOCOL_VERSION,
        "python": sys.version.split()[0],
        "pid": os.getpid(),
        "capabilities": capabilities(),
    }


def result_message(request_id: str, result: Any) -> dict[str, Any]:
    return {"v": PROTOCOL_VERSION, "id": request_id, "type": "result", "result": result}


def error_message(request_id: str | None, error: WorkerError) -> dict[str, Any]:
    return {"v": PROTOCOL_VERSION, "id": request_id, "type": "error", "error": error.to_dict()}


def progress_message(request_id: str, done: int, total: int | None, stage: str) -> dict[str, Any]:
    return {
        "v": PROTOCOL_VERSION,
        "id": request_id,
        "type": "progress",
        "done": done,
        "total": total,
        "unit": "bytes",
        "stage": stage,
    }


def parse_request(raw: bytes, known_ops: Collection[str]) -> Request:
    """Validate one request line. Raises :class:`RequestError` with the error to send back."""
    try:
        doc = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError, RecursionError) as exc:
        raise RequestError(None, WorkerError(Code.BAD_REQUEST, "request is not valid UTF-8 JSON")) from exc
    if not isinstance(doc, dict):
        raise RequestError(None, WorkerError(Code.BAD_REQUEST, "request must be a JSON object"))
    request_id = doc.get("id")
    if not isinstance(request_id, str) or not _ID_RE.match(request_id):
        raise RequestError(None, WorkerError(Code.BAD_REQUEST, "'id' must be 1-64 chars of [A-Za-z0-9_.-]"))
    version = doc.get("v")
    if type(version) is not int or version != PROTOCOL_VERSION:
        raise RequestError(
            request_id, WorkerError(Code.UNSUPPORTED_VERSION, f"protocol version must be {PROTOCOL_VERSION}")
        )
    op = doc.get("op")
    if not isinstance(op, str) or op not in known_ops:
        raise RequestError(request_id, WorkerError(Code.UNKNOWN_OP, "unknown operation"))
    params = doc.get("params", {})
    if params is None:
        params = {}
    if not isinstance(params, dict):
        raise RequestError(request_id, WorkerError(Code.BAD_REQUEST, "'params' must be an object"))
    return Request(request_id, op, params)
