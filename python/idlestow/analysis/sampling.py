"""Sampling plans and entropy."""

from __future__ import annotations

import math
from collections.abc import Mapping

MIN_SAMPLE_BYTES = 64 * 1024
MAX_SAMPLE_BYTES = 64 * 1024 * 1024
MIN_CHUNK_BYTES = 4096


def clamp_sample_bytes(value: int) -> int:
    return max(MIN_SAMPLE_BYTES, min(MAX_SAMPLE_BYTES, value))


def plan_sample(file_size: int, max_sample_bytes: int, chunk_size: int) -> tuple[list[tuple[int, int]], bool]:
    """Return ``([(offset, length), ...], full_read)``.

    Files up to ``max_sample_bytes`` are read completely (sequential chunks). Larger files are read
    as ``max_sample_bytes // chunk_size`` evenly spaced, non-overlapping chunks, the first at offset 0.
    The total planned length never exceeds ``max_sample_bytes``.
    """
    if file_size <= 0:
        return [], True
    chunk = max(1, min(chunk_size, max_sample_bytes))
    if file_size <= max_sample_bytes:
        return [(off, min(chunk, file_size - off)) for off in range(0, file_size, chunk)], True
    count = max(1, max_sample_bytes // chunk)
    if count == 1:
        return [(0, chunk)], False
    span = file_size - chunk
    return [(i * span // (count - 1), chunk) for i in range(count)], False


def shannon_entropy(counts: Mapping[int, int]) -> float:
    """Shannon entropy in bits per byte from a byte histogram."""
    total = sum(counts.values())
    if total <= 0:
        return 0.0
    entropy = 0.0
    for c in counts.values():
        if c:
            p = c / total
            entropy -= p * math.log2(p)
    return max(0.0, entropy)
