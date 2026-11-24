"""Streaming SHA-256."""

from __future__ import annotations

import hashlib
import os
import stat

from ..context import Context
from ..errors import Code, WorkerError
from ..fsutil import CHUNK_SIZE, fs_path


def hash_file(path: str, ctx: Context | None = None) -> tuple[str, int]:
    """Return ``(sha256_hex, size)`` reading in 1 MiB chunks; cancellable per chunk."""
    ctx = ctx or Context()
    real = fs_path(path)
    digest = hashlib.sha256()
    buf = bytearray(CHUNK_SIZE)
    view = memoryview(buf)
    done = 0
    with open(real, "rb", buffering=0) as handle:
        st = os.fstat(handle.fileno())
        if not stat.S_ISREG(st.st_mode):
            raise WorkerError(Code.BAD_REQUEST, "path is not a regular file")
        total = st.st_size
        while True:
            ctx.check()
            n = handle.readinto(buf)
            if not n:
                break
            digest.update(view[:n])
            done += n
            ctx.progress(done, total, "hashing")
    return digest.hexdigest(), done
