"""``python -m idlestow.benchmarking``: measure real throughput on synthetic data and print JSON."""

from __future__ import annotations

import argparse
import json
import os
import platform
import random
import statistics
import sys
import tempfile
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from .. import __version__, _optional
from ..analysis.estimate import estimate_compressibility
from ..compression.archive import create_archive
from ..fixtures import Corpus
from ..verification.archive import verify_archive
from ..verification.hashing import hash_file

MIB = 1024 * 1024


def _measure(fn: Callable[[], Any], repeat: int, bytes_processed: Callable[[Any], int]) -> dict[str, Any]:
    timings: list[float] = []
    last: Any = None
    for _ in range(repeat):
        start = time.perf_counter()
        last = fn()
        timings.append(time.perf_counter() - start)
    processed = bytes_processed(last)
    best, median = min(timings), statistics.median(timings)
    return {
        "bytes_processed": processed,
        "runs": repeat,
        "seconds_best": round(best, 6),
        "seconds_median": round(median, 6),
        "mib_per_s_best": round(processed / MIB / best, 2) if best > 0 else None,
        "mib_per_s_median": round(processed / MIB / median, 2) if median > 0 else None,
    }


def _bench_file(path: Path, tmp: Path, level: str, repeat: int, size: int) -> dict[str, Any]:
    results: dict[str, Any] = {}
    sha = hash_file(str(path))[0]
    results["hash"] = _measure(lambda: hash_file(str(path)), repeat, lambda _r: size)
    results["estimate"] = _measure(
        lambda: estimate_compressibility(str(path), level=level),
        repeat,
        lambda r: r["sampled_bytes"],
    )
    counter = iter(range(10_000))

    def make(fmt: str) -> dict[str, Any]:
        dest = tmp / f"{path.stem}-{next(counter)}.{fmt.replace('.', '_')}"
        return create_archive(str(path), str(dest), fmt, level, path.name, None, False, {})

    formats = ["zip"] + (["tar.zst"] if _optional.zstd_available() else [])
    for fmt in formats:
        key = fmt.replace(".", "_")
        archive: dict[str, Any] = {}

        def create(fmt: str = fmt, archive: dict[str, Any] = archive) -> dict[str, Any]:
            archive.update(make(fmt))
            return archive

        results[f"{key}_create"] = _measure(create, repeat, lambda _r: size)
        results[f"{key}_create"]["archive_size"] = archive["archive_size"]
        results[f"{key}_create"]["ratio"] = round(archive["archive_size"] / size, 4) if size else None
        results[f"{key}_verify"] = _measure(
            lambda a=archive, fmt=fmt: verify_archive(a["archive"], fmt, path.name, sha, size),
            repeat,
            lambda _r: size,
        )
    if not _optional.zstd_available():
        results["tar_zst"] = {"available": False}
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m idlestow.benchmarking", description=__doc__)
    parser.add_argument("--size-mib", type=int, default=32, help="size of each synthetic file")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--level", choices=("fast", "balanced", "maximum"), default="balanced")
    args = parser.parse_args(argv)
    if args.size_mib < 1 or args.repeat < 1:
        parser.error("--size-mib and --repeat must be >= 1")
    size = args.size_mib * MIB
    rng = random.Random(args.seed)
    report: dict[str, Any] = {
        "idlestow_version": __version__,
        "python": platform.python_version(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpu_count": os.cpu_count(),
        "zstandard_available": _optional.zstd_available(),
        "dataset": {
            "seed": args.seed,
            "files": ["compressible_text", "incompressible_random"],
            "size_bytes_each": size,
            "level": args.level,
            "repeat": args.repeat,
        },
        "notes": "Real measurements on this machine; files are freshly written so reads are likely served "
        "from the OS cache. Archive creation includes SHA-256 hashing and fsync.",
        "results": {},
    }
    with tempfile.TemporaryDirectory(prefix="idlestow-bench-") as tmp_name:
        tmp = Path(tmp_name)
        text = tmp / "compressible_text.txt"
        text.write_bytes(Corpus(rng).text(size))
        rand = tmp / "incompressible_random.bin"
        rand.write_bytes(rng.randbytes(size))
        work = tmp / "out"
        work.mkdir()
        for path in (text, rand):
            report["results"][path.name] = _bench_file(path, work, args.level, args.repeat, size)
    json.dump(report, sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
