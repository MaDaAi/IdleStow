from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, os.path.dirname(__file__))

from helpers import Harness  # noqa: E402

try:
    import zstandard  # noqa: F401

    HAVE_ZSTD = True
except ImportError:
    HAVE_ZSTD = False

requires_zstd = pytest.mark.skipif(not HAVE_ZSTD, reason="zstandard is not installed")
FORMATS = ["zip", pytest.param("tar.zst", marks=requires_zstd)]


@pytest.fixture
def harness():
    h = Harness()
    yield h
    if h.exit_code is None:
        h.finish()


@pytest.fixture
def text_file(tmp_path: Path) -> Path:
    p = tmp_path / "sample.txt"
    p.write_bytes(("The quick brown fox jumps over the lazy dog. " * 2000).encode())
    return p
