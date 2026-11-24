"""Lazy access to optional third-party modules (looked up at call time so tests can simulate absence)."""

from __future__ import annotations

import importlib
from types import ModuleType


def _try_import(name: str) -> ModuleType | None:
    try:
        return importlib.import_module(name)
    except ImportError:
        return None


def zstandard() -> ModuleType | None:
    return _try_import("zstandard")


def psutil() -> ModuleType | None:
    return _try_import("psutil")


def zstd_available() -> bool:
    return zstandard() is not None
