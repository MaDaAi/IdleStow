"""Compressibility estimation from a bounded, evenly spaced sample of a file."""

from __future__ import annotations

import math
import os
import stat
import zlib
from collections import Counter
from typing import Any

from .. import _optional
from ..compression.levels import LEVEL_NAMES, ZIP_LEVELS, ZSTD_LEVELS
from ..context import Context
from ..errors import Code, WorkerError
from ..fsutil import fs_path
from .formats import HEAD_BYTES, detect_format
from .sampling import clamp_sample_bytes, plan_sample, shannon_entropy

DEFAULT_MAX_SAMPLE = 4 * 1024 * 1024
DEFAULT_CHUNK = 256 * 1024
NTFS_UNIT = 64 * 1024
NTFS_CLUSTER = 4096
NTFS_LZNT1_FACTOR = 1.15
_MIN_CHUNK = 4096


def _ntfs_allocation(chunk: bytes) -> tuple[int, int]:
    """Return ``(original_allocation, estimated_compressed_allocation)`` in bytes for one chunk.

    Each 64 KiB unit saves whole 4 KiB clusters only if its compressed proxy size (deflate level 1
    times 1.15) needs fewer clusters than the unit occupies.
    """
    original = 0
    compressed = 0
    for start in range(0, len(chunk), NTFS_UNIT):
        unit = chunk[start : start + NTFS_UNIT]
        orig_clusters = math.ceil(len(unit) / NTFS_CLUSTER)
        proxy = len(zlib.compress(unit, 1)) * NTFS_LZNT1_FACTOR
        proxy_clusters = math.ceil(proxy / NTFS_CLUSTER)
        original += orig_clusters * NTFS_CLUSTER
        compressed += (proxy_clusters if proxy_clusters < orig_clusters else orig_clusters) * NTFS_CLUSTER
    return original, compressed


def estimate_compressibility(
    path: str,
    max_sample_bytes: int = DEFAULT_MAX_SAMPLE,
    chunk_size: int = DEFAULT_CHUNK,
    level: str = "balanced",
    ctx: Context | None = None,
) -> dict[str, Any]:
    """Estimate zip / zstd / NTFS savings. Never reads more than ``max_sample_bytes`` (clamped)."""
    ctx = ctx or Context()
    if level not in LEVEL_NAMES:
        raise WorkerError(Code.BAD_REQUEST, "level must be one of fast|balanced|maximum")
    max_sample = clamp_sample_bytes(max_sample_bytes)
    chunk = max(_MIN_CHUNK, min(chunk_size, max_sample))
    zstd = _optional.zstandard()
    zstd_comp = zstd.ZstdCompressor(level=ZSTD_LEVELS[level]) if zstd is not None else None
    zip_level = ZIP_LEVELS[level]

    counts: Counter[int] = Counter()
    sampled = zip_bytes = zstd_bytes = ntfs_orig = ntfs_comp = 0
    head = b""

    with open(fs_path(path), "rb") as handle:
        st = os.fstat(handle.fileno())
        if not stat.S_ISREG(st.st_mode):
            raise WorkerError(Code.BAD_REQUEST, "path is not a regular file")
        file_size = st.st_size
        plan, full_read = plan_sample(file_size, max_sample, chunk)
        for offset, length in plan:
            ctx.check()
            handle.seek(offset)
            data = handle.read(length)
            if not data:
                break
            if offset == 0:
                head = data[:HEAD_BYTES]
            sampled += len(data)
            counts.update(data)
            zip_bytes += len(zlib.compress(data, zip_level))
            if zstd_comp is not None:
                zstd_bytes += len(zstd_comp.compress(data))
            o, c = _ntfs_allocation(data)
            ntfs_orig += o
            ntfs_comp += c
            ctx.progress(sampled, sum(n for _, n in plan), "sampling")

    if sampled == 0:
        zip_ratio = 1.0
        zstd_ratio = 1.0 if zstd_comp is not None else None
        ntfs_ratio = 1.0
    else:
        zip_ratio = zip_bytes / sampled
        zstd_ratio = zstd_bytes / sampled if zstd_comp is not None else None
        ntfs_ratio = ntfs_comp / ntfs_orig if ntfs_orig else 1.0

    def savings(ratio: float) -> int:
        return max(0, file_size - round(ratio * file_size))

    file_alloc = math.ceil(file_size / NTFS_CLUSTER) * NTFS_CLUSTER
    return {
        "file_size": file_size,
        "sampled_bytes": sampled,
        "full_read": full_read,
        "detected_format": detect_format(head),
        "entropy_bits_per_byte": round(shannon_entropy(counts), 6),
        "zip_ratio": round(zip_ratio, 6),
        "zstd_ratio": round(zstd_ratio, 6) if zstd_ratio is not None else None,
        "ntfs_ratio": round(ntfs_ratio, 6),
        "estimated_zip_savings": savings(zip_ratio),
        "estimated_zstd_savings": savings(zstd_ratio) if zstd_ratio is not None else None,
        "estimated_ntfs_savings": max(0, round(file_alloc * (1.0 - ntfs_ratio))),
        "basis": "full" if full_read else "sampled",
    }
