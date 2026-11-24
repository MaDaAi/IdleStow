"""Compression level presets shared by archive creation and estimation."""

from __future__ import annotations

ZIP_LEVELS = {"fast": 1, "balanced": 6, "maximum": 9}
ZSTD_LEVELS = {"fast": 3, "balanced": 9, "maximum": 19}
LEVEL_NAMES = tuple(ZIP_LEVELS)
FORMATS = ("zip", "tar.zst")
