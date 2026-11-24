"""Protocol error codes and the exception type used throughout the worker."""

from __future__ import annotations

import errno
from typing import Any


class Code:
    """Error code strings defined by the IPC protocol."""

    BAD_REQUEST = "BAD_REQUEST"
    UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    UNKNOWN_OP = "UNKNOWN_OP"
    MESSAGE_TOO_LARGE = "MESSAGE_TOO_LARGE"
    UNSAFE_PATH = "UNSAFE_PATH"
    NOT_FOUND = "NOT_FOUND"
    EXISTS = "EXISTS"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    INTEGRITY_FAILURE = "INTEGRITY_FAILURE"
    NO_BENEFIT = "NO_BENEFIT"
    UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    CANCELLED = "CANCELLED"
    BUSY = "BUSY"
    IO_ERROR = "IO_ERROR"
    INTERNAL = "INTERNAL"


class WorkerError(Exception):
    """An error that maps 1:1 to a protocol ``error`` message."""

    def __init__(self, code: str, message: str, retryable: bool = False) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.retryable = retryable

    def to_dict(self) -> dict[str, Any]:
        return {"code": self.code, "message": self.message, "retryable": self.retryable}


_TRANSIENT_ERRNOS = {errno.EAGAIN, errno.EBUSY, errno.EINTR}
_TRANSIENT_WINERRORS = {32, 33}  # sharing / lock violation


def _describe(exc: OSError) -> str:
    text = exc.strerror or str(exc)
    if exc.filename:
        text = f"{text}: {exc.filename}"
    return text[:500]


def from_os_error(exc: OSError) -> WorkerError:
    """Translate an ``OSError`` into the closest protocol error."""
    message = _describe(exc)
    if isinstance(exc, FileNotFoundError | NotADirectoryError):
        return WorkerError(Code.NOT_FOUND, message)
    if isinstance(exc, FileExistsError):
        return WorkerError(Code.EXISTS, message)
    if isinstance(exc, PermissionError):
        return WorkerError(Code.PERMISSION_DENIED, message)
    if isinstance(exc, IsADirectoryError):
        return WorkerError(Code.BAD_REQUEST, message)
    winerror = getattr(exc, "winerror", None)
    if exc.errno == errno.ENAMETOOLONG or winerror == 206:
        return WorkerError(Code.UNSAFE_PATH, message)
    retryable = exc.errno in _TRANSIENT_ERRNOS or winerror in _TRANSIENT_WINERRORS
    return WorkerError(Code.IO_ERROR, message, retryable)
