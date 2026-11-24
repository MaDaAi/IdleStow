"""Per-request execution context: cancellation token and rate-limited progress."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable

from .errors import Code, WorkerError

ProgressCallback = Callable[[int, int | None, str], None]

PROGRESS_MIN_INTERVAL = 0.25  # seconds => at most 4 progress messages per second


class CancelToken:
    """Thread-safe cancellation flag."""

    def __init__(self) -> None:
        self._event = threading.Event()

    def cancel(self) -> None:
        self._event.set()

    @property
    def cancelled(self) -> bool:
        return self._event.is_set()


class Context:
    """Handed to long-running functions; they call :meth:`check` once per chunk."""

    def __init__(
        self,
        cancel: CancelToken | None = None,
        progress_cb: ProgressCallback | None = None,
        min_interval: float = PROGRESS_MIN_INTERVAL,
    ) -> None:
        self.cancel = cancel or CancelToken()
        self._progress_cb = progress_cb
        self._min_interval = min_interval
        self._last = float("-inf")

    def check(self) -> None:
        if self.cancel.cancelled:
            raise WorkerError(Code.CANCELLED, "request was cancelled")

    def progress(self, done: int, total: int | None, stage: str) -> None:
        if self._progress_cb is None:
            return
        now = time.monotonic()
        if now - self._last >= self._min_interval:
            self._last = now
            self._progress_cb(done, total, stage)
