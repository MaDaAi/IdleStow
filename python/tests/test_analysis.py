from __future__ import annotations

import os
import random
from pathlib import Path

import pytest
from conftest import HAVE_ZSTD
from helpers import Harness

from idlestow import _optional
from idlestow.analysis import detect_format, estimate_compressibility, plan_sample, shannon_entropy
from idlestow.analysis.estimate import NTFS_CLUSTER, _ntfs_allocation
from idlestow.context import CancelToken, Context
from idlestow.errors import WorkerError

MIB = 1024 * 1024
TEXT = ("lecture notes about sorting algorithms and data structures\n" * 5000).encode()


@pytest.mark.parametrize(
    "head,expected",
    [
        (b"\xff\xd8\xff\xe0abc", "jpeg"),
        (b"\x89PNG\r\n\x1a\nxxxx", "png"),
        (b"GIF89a....", "gif"),
        (b"GIF87a....", "gif"),
        (b"RIFF\x00\x00\x00\x00WEBPVP8 ", "webp"),
        (b"ID3\x03\x00\x00\x00", "mp3"),
        (b"\xff\xfb\x90\x00", "mp3"),
        (b"\x00\x00\x00\x18ftypmp42", "mp4"),
        (b"\x1aE\xdf\xa3\x93", "mkv"),
        (b"PK\x03\x04\x14\x00\x00\x00\x08\x00" + b"\x00" * 16 + b"\x08\x00\x00\x00data.txt", "zip"),
        (b"\x1f\x8b\x08\x00", "gzip"),
        (b"7z\xbc\xaf\x27\x1c\x00\x04", "7z"),
        (b"Rar!\x1a\x07\x00", "rar"),
        (b"\x28\xb5\x2f\xfd\x00", "zstd"),
        (b"\xfd7zXZ\x00\x00", "xz"),
        (b"BZh91AY&SY", "bzip2"),
        (b"%PDF-1.7\n", "pdf"),
        (b"SQLite format 3\x00" + b"\x00" * 20, "sqlite"),
        (b"MZ\x90\x00\x03", "exe"),
        (b"plain old text\nwith lines\n", "text"),
        ("Résumé – 日本語 text".encode(), "text"),
        (b"\x00\x01\x02\x03\xfe\xfd\x80\x81" * 10, "unknown"),
        (b"", "unknown"),
    ],
)
def test_detect_format_by_magic_bytes(head: bytes, expected: str):
    assert detect_format(head) == expected


def test_detect_ooxml_vs_zip():
    name = b"[Content_Types].xml"
    header = b"PK\x03\x04" + b"\x14\x00" * 11 + len(name).to_bytes(2, "little") + b"\x00\x00" + name
    assert len(header) >= 30 and header[26:28] == len(name).to_bytes(2, "little")
    assert detect_format(header) == "ooxml"


def test_detect_ignores_extension(tmp_path: Path):
    p = tmp_path / "actually_png.txt"
    p.write_bytes(b"\x89PNG\r\n\x1a\n" + os.urandom(1000))
    assert estimate_compressibility(str(p))["detected_format"] == "png"


def test_entropy_bounds():
    assert shannon_entropy({}) == 0.0
    assert shannon_entropy({7: 1000}) == 0.0
    assert shannon_entropy({i: 10 for i in range(256)}) == pytest.approx(8.0)


def test_plan_sample_properties():
    plan, full = plan_sample(10 * MIB, 4 * MIB, 256 * 1024)
    assert not full and sum(n for _, n in plan) <= 4 * MIB and plan[0][0] == 0
    offsets = [o for o, _ in plan]
    assert offsets == sorted(offsets) and all(
        b - a >= 256 * 1024 for a, b in zip(offsets, offsets[1:], strict=False)
    )
    assert plan[-1][0] + plan[-1][1] == 10 * MIB  # evenly spaced up to the end of the file
    plan, full = plan_sample(100, 4 * MIB, 256 * 1024)
    assert full and plan == [(0, 100)]
    assert plan_sample(0, 4 * MIB, 1024) == ([], True)


def test_estimate_text_full_read(tmp_path: Path):
    p = tmp_path / "t.txt"
    p.write_bytes(TEXT)
    r = estimate_compressibility(str(p))
    assert r["basis"] == "full" and r["full_read"] is True
    assert r["file_size"] == r["sampled_bytes"] == len(TEXT)
    assert r["detected_format"] == "text"
    assert r["entropy_bits_per_byte"] < 5
    assert r["zip_ratio"] < 0.1
    assert r["ntfs_ratio"] < 1.0
    assert 0 < r["estimated_zip_savings"] <= len(TEXT)
    assert r["estimated_ntfs_savings"] > 0 and r["estimated_ntfs_savings"] % NTFS_CLUSTER == 0
    if HAVE_ZSTD:
        assert r["zstd_ratio"] is not None and r["estimated_zstd_savings"] > 0
    else:
        assert r["zstd_ratio"] is None and r["estimated_zstd_savings"] is None


def test_estimate_without_zstandard(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(_optional, "zstandard", lambda: None)
    p = tmp_path / "t.txt"
    p.write_bytes(TEXT)
    r = estimate_compressibility(str(p))
    assert r["zstd_ratio"] is None and r["estimated_zstd_savings"] is None


def test_estimate_random_data(tmp_path: Path):
    p = tmp_path / "r.bin"
    p.write_bytes(random.Random(1).randbytes(512 * 1024))
    r = estimate_compressibility(str(p))
    assert r["entropy_bits_per_byte"] > 7.99
    assert r["zip_ratio"] > 0.99 and r["estimated_zip_savings"] == 0
    assert r["ntfs_ratio"] == 1.0 and r["estimated_ntfs_savings"] == 0
    assert r["detected_format"] == "unknown"


def test_estimate_sample_is_bounded_and_labelled(tmp_path: Path):
    p = tmp_path / "big.bin"
    rng = random.Random(3)
    with p.open("wb") as f:
        for i in range(40):
            f.write(TEXT[: 512 * 1024] if i % 2 else rng.randbytes(512 * 1024))
    size = p.stat().st_size
    r = estimate_compressibility(str(p), max_sample_bytes=MIB, chunk_size=128 * 1024)
    assert r["file_size"] == size
    assert 0 < r["sampled_bytes"] <= MIB and r["basis"] == "sampled" and r["full_read"] is False


def test_estimate_clamps_max_sample_to_min_and_max(tmp_path: Path):
    p = tmp_path / "f.bin"
    p.write_bytes(os.urandom(512 * 1024))
    r = estimate_compressibility(str(p), max_sample_bytes=1, chunk_size=10**9)
    assert r["sampled_bytes"] <= 64 * 1024 and r["basis"] == "sampled"
    r = estimate_compressibility(str(p), max_sample_bytes=10**12)
    assert r["basis"] == "full"  # clamped to 64 MiB, file is smaller


def test_estimate_never_reads_more_than_bound(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    p = tmp_path / "big.bin"
    with p.open("wb") as f:
        f.truncate(32 * MIB)
    total = []
    import builtins

    real_open = builtins.open

    class Counting:
        def __init__(self, f):
            self._f = f

        def read(self, n=-1):
            data = self._f.read(n)
            total.append(len(data))
            return data

        def __getattr__(self, name):
            return getattr(self._f, name)

        def __enter__(self):
            return self

        def __exit__(self, *a):
            self._f.close()

    monkeypatch.setattr(
        "idlestow.analysis.estimate.open", lambda *a, **k: Counting(real_open(*a, **k)), raising=False
    )
    r = estimate_compressibility(str(p), max_sample_bytes=MIB)
    assert sum(total) <= MIB and r["sampled_bytes"] == sum(total)


def test_estimate_zero_byte_file(tmp_path: Path):
    p = tmp_path / "empty"
    p.write_bytes(b"")
    r = estimate_compressibility(str(p))
    assert r["file_size"] == 0 and r["sampled_bytes"] == 0 and r["basis"] == "full"
    assert r["zip_ratio"] == 1.0 and r["estimated_zip_savings"] == 0 and r["estimated_ntfs_savings"] == 0


def test_estimate_unicode_name_and_bad_level(tmp_path: Path):
    p = tmp_path / "Résumé – 日本語.txt"
    p.write_bytes(TEXT)
    assert estimate_compressibility(str(p))["detected_format"] == "text"
    with pytest.raises(WorkerError):
        estimate_compressibility(str(p), level="turbo")


def test_estimate_cancel(tmp_path: Path):
    p = tmp_path / "t.txt"
    p.write_bytes(TEXT * 20)
    token = CancelToken()
    token.cancel()
    with pytest.raises(WorkerError) as e:
        estimate_compressibility(str(p), ctx=Context(token))
    assert e.value.code == "CANCELLED"


def test_ntfs_unit_rule():
    zeros_unit = bytes(64 * 1024)
    orig, comp = _ntfs_allocation(zeros_unit)
    assert orig == 64 * 1024 and comp == NTFS_CLUSTER  # one cluster is enough for a zero unit
    orig, comp = _ntfs_allocation(os.urandom(64 * 1024))
    assert comp == orig  # incompressible units keep all 16 clusters


def test_estimate_over_protocol(harness: Harness, tmp_path: Path):
    p = tmp_path / "t.txt"
    p.write_bytes(TEXT)
    res = harness.call("estimate_compressibility", {"path": str(p), "level": "fast"})["result"]
    assert res["basis"] == "full" and res["zip_ratio"] < 0.2
    bad = harness.call("estimate_compressibility", {"path": str(p), "level": "nope"}, rid="b")
    assert bad["error"]["code"] == "BAD_REQUEST"
    bad = harness.call("estimate_compressibility", {"path": str(p), "max_sample_bytes": "x"}, rid="b2")
    assert bad["error"]["code"] == "BAD_REQUEST"
