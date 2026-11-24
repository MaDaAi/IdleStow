"""Line framing: bounded reads from a binary stream and thread-safe JSON line writes."""

from __future__ import annotations

import enum
import json
import threading
from collections.abc import Callable
from typing import Any, BinaryIO

from ..errors import Code, WorkerError

MAX_LINE_BYTES = 1024 * 1024
_DISCARD_READ = 64 * 1024


class LineKind(enum.Enum):
    LINE = "line"
    OVERSIZE = "oversize"
    EOF = "eof"


def _discard_rest_of_line(stream: BinaryIO) -> None:
    while True:
        chunk = stream.readline(_DISCARD_READ)
        if not chunk or chunk.endswith(b"\n"):
            return


def read_line(stream: BinaryIO, limit: int = MAX_LINE_BYTES) -> tuple[LineKind, bytes]:
    """Read one line without ever buffering more than ``limit + 2`` bytes.

    A line longer than ``limit`` (excluding the newline) yields ``OVERSIZE`` and the remainder of
    that line is discarded, so the next call starts at the following line.
    """
    data = stream.readline(limit + 2)
    if not data:
        return LineKind.EOF, b""
    if data.endswith(b"\n"):
        body = data[:-1]
        if body.endswith(b"\r"):
            body = body[:-1]
        if len(body) > limit:
            return LineKind.OVERSIZE, b""
        return LineKind.LINE, body
    if len(data) >= limit + 2:
        _discard_rest_of_line(stream)
        return LineKind.OVERSIZE, b""
    return LineKind.LINE, data  # final line without trailing newline at EOF


class MessageWriter:
    """Serialises protocol messages to the (binary) protocol stream: one lock, flush per message."""

    def __init__(self, stream: BinaryIO, on_broken: Callable[[], None] | None = None) -> None:
        self._stream = stream
        self._lock = threading.Lock()
        self._on_broken = on_broken
        self.broken = False

    @staticmethod
    def encode(message: dict[str, Any]) -> bytes:
        return json.dumps(message, ensure_ascii=True, separators=(",", ":")).encode("ascii") + b"\n"

    def send(self, message: dict[str, Any]) -> bool:
        data = self.encode(message)
        if len(data) - 1 > MAX_LINE_BYTES:
            if message.get("type") != "result":
                return False
            err = WorkerError(Code.LIMIT_EXCEEDED, "response exceeds the 1 MiB line limit")
            data = self.encode({"v": 1, "id": message.get("id"), "type": "error", "error": err.to_dict()})
        with self._lock:
            if self.broken:
                return False
            try:
                self._stream.write(data)
                self._stream.flush()
            except (OSError, ValueError):
                self.broken = True
                if self._on_broken is not None:
                    self._on_broken()
                return False
        return True
