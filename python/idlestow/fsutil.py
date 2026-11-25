"""Filesystem helpers: long-path handling, absolute path validation, safe cleanup."""

from __future__ import annotations

import ntpath
import os
import re
from typing import TYPE_CHECKING

from .errors import Code, WorkerError

if TYPE_CHECKING:
    from pathlib import Path

CHUNK_SIZE = 1024 * 1024
MAX_PATH_CHARS = 32_000
LONG_PATH_THRESHOLD = 240
_WIN_PREFIX = "\\\\?\\"
_DRIVE_RE = re.compile(r"^[A-Za-z]:")


def fs_path(path: str, *, windows: bool | None = None) -> str:
    """Path to hand to the OS. On Windows, long absolute paths get the extended-length prefix."""
    if windows is None:
        windows = os.name == "nt"
    if not windows or len(path) <= LONG_PATH_THRESHOLD or path.startswith(_WIN_PREFIX):
        return path
    norm = ntpath.normpath(path)
    if norm.startswith("\\\\"):
        return _WIN_PREFIX + "UNC\\" + norm[2:]
    return _WIN_PREFIX + norm


def is_absolute(path: str, *, windows: bool | None = None) -> bool:
    if windows is None:
        windows = os.name == "nt"
    if windows:
        drive, rest = ntpath.splitdrive(path)
        return bool(drive) and rest[:1] in ("\\", "/")
    return path.startswith("/")


def validate_abs_path(value: object, name: str) -> str:
    """Validate a path parameter per the protocol (absolute, no NUL, <= 32000 chars)."""
    if not isinstance(value, str) or not value:
        raise WorkerError(Code.BAD_REQUEST, f"'{name}' must be a non-empty string")
    if "\0" in value:
        raise WorkerError(Code.UNSAFE_PATH, f"'{name}' contains a NUL character")
    if len(value) > MAX_PATH_CHARS:
        raise WorkerError(Code.UNSAFE_PATH, f"'{name}' is longer than {MAX_PATH_CHARS} characters")
    if not is_absolute(value):
        raise WorkerError(Code.UNSAFE_PATH, f"'{name}' must be an absolute path")
    return value


def remove_quiet(path: str) -> None:
    """Best-effort removal of a partial output file that this process created."""
    try:
        os.unlink(fs_path(path))
    except OSError:
        pass


def lexists(path: str) -> bool:
    return os.path.lexists(fs_path(path))


def is_root_or_home(path: Path) -> bool:
    """True if ``path`` is a filesystem root or the user's home directory itself."""
    from pathlib import Path  # lazy: keeps worker start-up fast

    resolved = path.resolve()
    if resolved.parent == resolved:
        return True
    try:
        return resolved == Path.home().resolve()
    except RuntimeError:
        return False


def drive_like(name: str) -> bool:
    return bool(_DRIVE_RE.match(name))
