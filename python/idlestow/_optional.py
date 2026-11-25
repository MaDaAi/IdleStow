"""Lazy access to optional third-party modules (looked up at call time so tests can simulate absence)."""

from __future__ import annotations

import importlib
import importlib.util
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


_STOCK = {"zstandard": zstandard, "psutil": psutil}


def _present(name: str) -> bool:
    """True if the module can be located without importing it (importing is slow).

    If the accessor was replaced (tests simulate absence that way) the replacement is honoured.
    """
    accessor = globals()[name]
    if accessor is not _STOCK[name]:
        return accessor() is not None
    try:
        return importlib.util.find_spec(name) is not None
    except (ImportError, ValueError):
        return False


def zstd_present() -> bool:
    return _present("zstandard")


def psutil_present() -> bool:
    return _present("psutil")
