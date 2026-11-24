"""The IPC server: reader loop, control ops, single FIFO work thread."""

from __future__ import annotations

import logging
import queue
import threading
import time
from dataclasses import dataclass
from typing import Any, BinaryIO

from ..context import CancelToken, Context
from ..errors import Code, WorkerError, from_os_error
from .framing import LineKind, MessageWriter, read_line
from .ops import WORK_OPS, Handler
from .params import str_param
from .protocol import (
    Request,
    RequestError,
    capabilities,
    error_message,
    parse_request,
    progress_message,
    ready_message,
    result_message,
)

log = logging.getLogger("idlestow.worker")

QUEUE_CAP = 16
CONTROL_OPS = frozenset({"ping", "capabilities", "cancel", "shutdown"})
JOIN_TIMEOUT_SECONDS = 5.0


@dataclass
class _Job:
    request: Request
    token: CancelToken
    handler: Handler


class Server:
    def __init__(
        self,
        stdin: BinaryIO,
        stdout: BinaryIO,
        work_ops: dict[str, Handler] | None = None,
        queue_cap: int = QUEUE_CAP,
    ) -> None:
        self._stdin = stdin
        self._ops = dict(WORK_OPS if work_ops is None else work_ops)
        self._known = CONTROL_OPS | set(self._ops)
        self._queue: queue.Queue[_Job] = queue.Queue(maxsize=queue_cap)
        self._stopping = threading.Event()
        self._lock = threading.Lock()
        self._inflight: dict[str, CancelToken] = {}
        self._writer = MessageWriter(stdout, on_broken=self._stopping.set)
        self._thread = threading.Thread(target=self._work_loop, name="idlestow-work", daemon=True)

    # -- lifecycle ---------------------------------------------------------------------------

    def serve(self) -> int:
        """Emit the handshake and serve until EOF or ``shutdown``. Returns the process exit code."""
        self._writer.send(ready_message())
        self._thread.start()
        try:
            while not self._stopping.is_set():
                kind, line = read_line(self._stdin)
                if kind is LineKind.EOF:
                    break
                if kind is LineKind.OVERSIZE:
                    err = WorkerError(Code.MESSAGE_TOO_LARGE, "request line exceeds 1 MiB")
                    self._writer.send(error_message(None, err))
                    continue
                if not line.strip():
                    continue
                self._handle_line(line)
        finally:
            self._stop()
        return 0

    def _stop(self) -> None:
        self._stopping.set()
        with self._lock:
            for token in self._inflight.values():
                token.cancel()
        self._thread.join(JOIN_TIMEOUT_SECONDS)

    # -- reader side -------------------------------------------------------------------------

    def _handle_line(self, line: bytes) -> None:
        try:
            request = parse_request(line, self._known)
        except RequestError as exc:
            self._writer.send(error_message(exc.request_id, exc.error))
            return
        with self._lock:
            if request.id in self._inflight:
                duplicate = True
            else:
                duplicate = False
                if request.op not in CONTROL_OPS:
                    token = CancelToken()
                    self._inflight[request.id] = token
        if duplicate:
            err = WorkerError(Code.BAD_REQUEST, "a request with this id is already in flight")
            self._writer.send(error_message(request.id, err))
            return
        if request.op in CONTROL_OPS:
            self._run_control(request)
            return
        job = _Job(request, token, self._ops[request.op])
        try:
            self._queue.put_nowait(job)
        except queue.Full:
            self._finish(request.id)
            err = WorkerError(Code.BUSY, "work queue is full", retryable=True)
            self._writer.send(error_message(request.id, err))

    def _run_control(self, request: Request) -> None:
        rid = request.id
        try:
            if request.op == "ping":
                result: Any = {"pong": True, "time_ms": int(time.time() * 1000)}
            elif request.op == "capabilities":
                result = capabilities()
            elif request.op == "cancel":
                target = str_param(request.params, "target")
                with self._lock:
                    token = self._inflight.get(target)
                if token is not None:
                    token.cancel()
                result = {"cancelled": token is not None}
            else:  # shutdown
                self._writer.send(result_message(rid, {"bye": True}))
                self._stopping.set()
                with self._lock:
                    for token in self._inflight.values():
                        token.cancel()
                return
        except WorkerError as err:
            self._writer.send(error_message(rid, err))
            return
        self._writer.send(result_message(rid, result))

    # -- work thread -------------------------------------------------------------------------

    def _finish(self, request_id: str) -> None:
        with self._lock:
            self._inflight.pop(request_id, None)

    def _work_loop(self) -> None:
        while True:
            try:
                job = self._queue.get(timeout=0.1)
            except queue.Empty:
                if self._stopping.is_set():
                    return
                continue
            self._run_job(job)

    def _run_job(self, job: _Job) -> None:
        rid = job.request.id

        def on_progress(done: int, total: int | None, stage: str) -> None:
            self._writer.send(progress_message(rid, done, total, stage))

        ctx = Context(job.token, on_progress)
        try:
            ctx.check()
            message = result_message(rid, job.handler(job.request.params, ctx))
        except WorkerError as err:
            message = error_message(rid, err)
        except OSError as exc:
            message = error_message(rid, from_os_error(exc))
        except Exception as exc:  # noqa: BLE001 - the worker must survive any handler bug
            log.exception("internal error in op %s", job.request.op)
            err = WorkerError(Code.INTERNAL, f"{type(exc).__name__}: {exc}"[:500])
            message = error_message(rid, err)
        self._finish(rid)
        self._writer.send(message)
