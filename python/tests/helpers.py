"""Shared test helpers: in-memory protocol harness and crafted-archive builders."""

from __future__ import annotations

import io
import json
import queue
import tarfile
import threading
import time
import zipfile
from pathlib import Path
from typing import Any

from idlestow.ipc.server import Server


class PipeIn:
    """A blocking, binary, readline()-capable stand-in for the worker's stdin."""

    def __init__(self) -> None:
        self._q: queue.Queue[bytes | None] = queue.Queue()
        self._buf = bytearray()
        self._eof = False

    def feed(self, data: bytes) -> None:
        self._q.put(data)

    def close(self) -> None:
        self._q.put(None)

    def readline(self, size: int = -1) -> bytes:
        while True:
            idx = self._buf.find(b"\n")
            if idx >= 0 and (size < 0 or idx + 1 <= size):
                end = idx + 1
                break
            if size >= 0 and len(self._buf) >= size:
                end = size
                break
            if self._eof:
                end = len(self._buf)
                break
            item = self._q.get()
            if item is None:
                self._eof = True
            else:
                self._buf.extend(item)
        out = bytes(self._buf[:end])
        del self._buf[:end]
        return out


class Capture:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.data = bytearray()

    def write(self, b: bytes) -> int:
        with self._lock:
            self.data.extend(b)
        return len(b)

    def flush(self) -> None:
        pass

    def messages(self) -> list[dict[str, Any]]:
        with self._lock:
            raw = bytes(self.data)
        return [json.loads(line) for line in raw.split(b"\n") if line]


class Harness:
    def __init__(self, work_ops: dict[str, Any] | None = None, queue_cap: int = 16) -> None:
        self.stdin = PipeIn()
        self.out = Capture()
        self.server = Server(self.stdin, self.out, work_ops=work_ops, queue_cap=queue_cap)  # type: ignore[arg-type]
        self.exit_code: int | None = None
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        self.exit_code = self.server.serve()

    def send(self, op: str, params: dict[str, Any] | None = None, rid: str = "r1", **extra: Any) -> None:
        msg: dict[str, Any] = {"v": 1, "id": rid, "op": op, "params": params or {}}
        msg.update(extra)
        self.stdin.feed(json.dumps(msg).encode() + b"\n")

    def raw(self, data: bytes) -> None:
        self.stdin.feed(data)

    def wait(self, predicate: Any, timeout: float = 20.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for m in self.out.messages():
                if predicate(m):
                    return m
            time.sleep(0.01)
        raise AssertionError(f"timeout; messages so far: {self.out.messages()}")

    def terminal(self, rid: str, timeout: float = 20.0) -> dict[str, Any]:
        return self.wait(lambda m: m.get("id") == rid and m.get("type") in ("result", "error"), timeout)

    def call(self, op: str, params: dict[str, Any] | None = None, rid: str = "r1") -> dict[str, Any]:
        self.send(op, params, rid)
        return self.terminal(rid)

    def finish(self) -> None:
        self.stdin.close()
        self._thread.join(10)
        assert not self._thread.is_alive()


def make_zip(path: Path, members: list[tuple[str, bytes]]) -> None:
    """Craft a zip with arbitrary member names (bypassing zipfile's name sanitising helpers)."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in members:
            info = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(info, data)


def make_tar_zst(path: Path, members: list[tuple[tarfile.TarInfo, bytes]]) -> None:
    import zstandard

    with path.open("wb") as raw, zstandard.ZstdCompressor().stream_writer(raw, closefd=False) as w:
        with tarfile.open(fileobj=w, mode="w|") as tf:
            for info, data in members:
                tf.addfile(info, io.BytesIO(data) if info.isreg() else None)


def reg(name: str, data: bytes) -> tuple[tarfile.TarInfo, bytes]:
    info = tarfile.TarInfo(name)
    info.size = len(data)
    return info, data
