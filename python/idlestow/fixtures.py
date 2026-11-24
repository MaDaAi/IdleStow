"""Generate SYNTHETIC test trees (``python -m idlestow.fixtures``). Never touches real user data."""

from __future__ import annotations

import argparse
import gzip
import io
import json
import os
import random
import sys
import time
import zipfile
from pathlib import Path

from .fsutil import fs_path, is_root_or_home

DAY = 86_400
MIB = 1024 * 1024

_WORDS = (
    "analysis assignment algorithm array binary branch buffer cache class compile concurrency data "
    "database design dictionary exception function graph hash heap inheritance interface iterate "
    "lecture lambda library lists memory method module network object operator parser pattern pointer "
    "polymorphism process queue recursion refactor schedule semester sorting stack string syntax thread "
    "tree variable vector exam notes chapter theorem proof lemma summary question answer reference"
).split()


class Corpus:
    """Deterministic pool of repetitive, realistic-looking text."""

    def __init__(self, rng: random.Random, sentences: int = 300) -> None:
        self._rng = rng
        self._sentences = [
            " ".join(rng.choice(_WORDS) for _ in range(rng.randint(6, 16))).capitalize() + "."
            for _ in range(sentences)
        ]

    def text(self, size: int, prefix: str = "") -> bytes:
        parts: list[str] = []
        total = 0
        n = 0
        while total < size:
            n += 1
            line = f"{prefix}{n:06d} {self._rng.choice(self._sentences)}\n"
            parts.append(line)
            total += len(line)
        return "".join(parts).encode("utf-8")[:size]


_EPOCH_DIFF_100NS = 116_444_736_000_000_000  # 1601-01-01 to 1970-01-01 in 100 ns ticks


def _set_creation_time_windows(path: str, epoch_seconds: float) -> None:
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateFileW.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    kernel32.CreateFileW.restype = wintypes.HANDLE
    kernel32.SetFileTime.argtypes = [wintypes.HANDLE, wintypes.LPVOID, wintypes.LPVOID, wintypes.LPVOID]
    kernel32.SetFileTime.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    file_write_attributes, share_all, open_existing, backup_semantics = 0x100, 7, 3, 0x02000000
    handle = kernel32.CreateFileW(
        fs_path(path), file_write_attributes, share_all, None, open_existing, backup_semantics, None
    )
    if handle in (None, ctypes.c_void_p(-1).value):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        ticks = int(epoch_seconds * 10_000_000) + _EPOCH_DIFF_100NS
        filetime = wintypes.FILETIME(ticks & 0xFFFFFFFF, ticks >> 32)
        if not kernel32.SetFileTime(handle, ctypes.byref(filetime), None, None):
            raise ctypes.WinError(ctypes.get_last_error())
    finally:
        kernel32.CloseHandle(handle)


def set_file_times(path: str | os.PathLike[str], epoch_seconds: float) -> None:
    """Set creation (Windows only), access and modification time to ``epoch_seconds``.

    atime/mtime are set last because opening a handle / SetFileTime may disturb them.
    """
    text = os.fspath(path)
    if os.name == "nt":
        _set_creation_time_windows(text, epoch_seconds)
    os.utime(fs_path(text), (epoch_seconds, epoch_seconds))


def _write(path: Path, data: bytes, mtime: float | None = None) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    if mtime is not None:
        set_file_times(path, mtime)
    return len(data)


def _docx(corpus: Corpus, size: int) -> bytes:
    buf = io.BytesIO()
    body = corpus.text(size).decode("utf-8").replace("&", "and").replace("<", " ").replace(">", " ")
    paragraphs = "".join(f"<w:p><w:r><w:t>{line}</w:t></w:r></w:p>" for line in body.splitlines())
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types/>')
        zf.writestr("word/document.xml", f"<w:document><w:body>{paragraphs}</w:body></w:document>")
    return buf.getvalue()


def _pdf(corpus: Corpus, size: int) -> bytes:
    body = corpus.text(size).decode("utf-8")
    head = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
    return f"{head}{body}\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n".encode()


def build_mixed(out: Path, seed: int, now: float) -> dict[str, int]:
    rng = random.Random(seed)
    corpus = Corpus(rng)
    recent, d45, d90 = now - 3600, now - 45 * DAY, now - 90 * DAY
    uni = out / "Documents" / "University"
    prev = uni / "PreviousSemester"
    files = [
        (uni / "CurrentAssignment.docx", _docx(corpus, 512 * 1024), recent),
        (uni / "ProgrammingProject.java", corpus.text(2 * MIB, "// "), recent),
        (uni / "OldLectureNotes.txt", corpus.text(2 * MIB), d45),
        (prev / "Assignment1.txt", corpus.text(2 * MIB), d90),
        (prev / "Assignment2.txt", corpus.text(2 * MIB), d90),
        (prev / "LectureNotes.txt", corpus.text(2 * MIB), d90),
        (prev / "RandomData.bin", rng.randbytes(2 * MIB), d90),
        (out / "Documents" / "PersonalDocuments" / "ImportantDocument.pdf", _pdf(corpus, 512 * 1024), d90),
        (uni / "Résumé – 日本語.txt", corpus.text(256 * 1024), d45),
        (uni / "notes with spaces.txt", corpus.text(256 * 1024), d45),
    ]
    total = 0
    for path, data, mtime in files:
        total += _write(path, data, mtime)
    return {"files": len(files), "bytes": total}


def _benchmark_size(rng: random.Random) -> int:
    roll = rng.random()
    if roll < 0.80:
        return rng.randint(256, 2 * 1024)
    if roll < 0.97:
        return rng.randint(2 * 1024, 16 * 1024)
    if roll < 0.998:
        return rng.randint(16 * 1024, 128 * 1024)
    return rng.randint(128 * 1024, 512 * 1024)


def _benchmark_payload(rng: random.Random, corpus: Corpus, size: int, kind: int) -> tuple[str, bytes]:
    if size == 0:
        return ".txt", b""
    if kind == 0:
        return ".txt", corpus.text(size)
    if kind == 1:
        return ".java", corpus.text(size, "// ")
    if kind == 2:
        rows = [{"i": i, "v": rng.randint(0, 99)} for i in range(max(1, size // 24))]
        return ".json", json.dumps(rows).encode()[:size]
    if kind == 3:
        return ".bin", rng.randbytes(size)
    if kind == 4:
        return ".jpg", b"\xff\xd8\xff\xe0" + rng.randbytes(max(0, size - 4))
    if kind == 5:
        return ".gz", gzip.compress(corpus.text(size * 3), 6, mtime=0)
    return ".dat", bytes(size)  # all zeros


def build_benchmark(out: Path, seed: int, now: float, files: int) -> dict[str, int]:
    rng = random.Random(seed)
    corpus = Corpus(rng, sentences=600)
    total = 0
    for i in range(files):
        depth = rng.randint(1, 4)
        parts = [f"d{rng.randint(0, 9)}" for _ in range(depth)]
        size = 0 if rng.random() < 0.01 else _benchmark_size(rng)
        ext, data = _benchmark_payload(rng, corpus, size, rng.choice((0, 0, 0, 1, 2, 3, 4, 5, 6)))
        age_days = rng.uniform(0, 14) if rng.random() < 0.3 else rng.uniform(14, 400)
        mtime = now - age_days * DAY
        total += _write(out.joinpath(*parts, f"file_{i:07d}{ext}"), data, mtime)
    return {"files": files, "bytes": total}


def generate(
    out: Path, preset: str, files: int, seed: int, force: bool, now: float | None = None
) -> dict[str, object]:
    """Create the tree. Raises ``ValueError`` for refused targets."""
    if out.exists() and not out.is_dir():
        raise ValueError(f"{out} exists and is not a directory")
    if is_root_or_home(out):
        raise ValueError("refusing to write into a filesystem root or the home directory itself")
    if out.exists() and any(out.iterdir()) and not force:
        raise ValueError(f"{out} is not empty (use --force to write into it anyway)")
    out.mkdir(parents=True, exist_ok=True)
    stamp = time.time() if now is None else now
    stats = build_mixed(out, seed, stamp) if preset == "mixed" else build_benchmark(out, seed, stamp, files)
    return {"out": str(out), "preset": preset, "seed": seed, **stats}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m idlestow.fixtures", description=__doc__)
    parser.add_argument("--out", required=True, type=Path, help="target directory (must be empty)")
    parser.add_argument("--preset", choices=("mixed", "benchmark"), default="mixed")
    parser.add_argument("--files", type=int, default=1000, help="file count (benchmark preset)")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--force", action="store_true", help="allow a non-empty target directory")
    args = parser.parse_args(argv)
    if not 1 <= args.files <= 5_000_000:
        parser.error("--files must be between 1 and 5000000")
    try:
        summary = generate(args.out.absolute(), args.preset, args.files, args.seed, args.force)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main())
