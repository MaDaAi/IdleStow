"""Validation of archive member / entry names."""

from __future__ import annotations

from .errors import Code, WorkerError
from .fsutil import drive_like

MANIFEST_NAME = ".idlestow-manifest.json"
MANIFEST_MAX_BYTES = 64 * 1024
MAX_ENTRY_NAME_CHARS = 255


def is_unsafe_member_name(name: str) -> bool:
    """True if the name could escape an extraction directory or is otherwise suspicious."""
    if not name:
        return True
    if any(ord(c) < 32 or ord(c) == 127 for c in name):
        return True
    if "\\" in name or name.startswith("/") or drive_like(name):
        return True
    return any(part == ".." for part in name.split("/"))


def validate_entry_name(name: object) -> str:
    """``entry_name`` must be a plain file name (no separators, ``..``, drive letters, control chars)."""
    if not isinstance(name, str) or not name:
        raise WorkerError(Code.BAD_REQUEST, "'entry_name' must be a non-empty string")
    if len(name) > MAX_ENTRY_NAME_CHARS:
        raise WorkerError(Code.UNSAFE_PATH, "'entry_name' is too long")
    if is_unsafe_member_name(name) or "/" in name or name in (".", ".."):
        raise WorkerError(Code.UNSAFE_PATH, "'entry_name' must be a plain file name")
    if name == MANIFEST_NAME:
        raise WorkerError(Code.UNSAFE_PATH, "'entry_name' collides with the manifest name")
    return name
