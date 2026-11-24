from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest

from idlestow import fixtures
from idlestow.analysis import estimate_compressibility

DAY = 86400
MIB = 1024 * 1024


@pytest.mark.skipif(os.name != "nt", reason="creation time is settable on Windows only")
def test_set_file_times_sets_birthtime(tmp_path: Path):
    p = tmp_path / "f.txt"
    p.write_text("x")
    target = time.time() - 90 * DAY
    fixtures.set_file_times(p, target)
    st = p.stat()
    assert abs(st.st_birthtime - target) < 2
    assert abs(st.st_mtime - target) < 2 and abs(st.st_atime - target) < 2


def test_set_file_times_mtime_everywhere(tmp_path: Path):
    p = tmp_path / "f.txt"
    p.write_text("x")
    target = time.time() - 45 * DAY
    fixtures.set_file_times(p, target)
    assert abs(p.stat().st_mtime - target) < 2


def test_mixed_scenario(tmp_path: Path):
    out = tmp_path / "tree"
    now = time.time()
    summary = fixtures.generate(out, "mixed", 0, 1, False, now=now)
    assert summary["files"] == 10
    uni = out / "Documents" / "University"
    prev = uni / "PreviousSemester"
    expected = {
        uni / "CurrentAssignment.docx": 0,
        uni / "ProgrammingProject.java": 0,
        uni / "OldLectureNotes.txt": 45,
        prev / "Assignment1.txt": 90,
        prev / "Assignment2.txt": 90,
        prev / "LectureNotes.txt": 90,
        out / "Documents" / "PersonalDocuments" / "ImportantDocument.pdf": 90,
    }
    for path, age in expected.items():
        st = path.stat()
        assert abs((now - st.st_mtime) / DAY - age) < 0.1, path
        assert abs((now - st.st_atime) / DAY - age) < 0.1, path
        if os.name == "nt":
            assert abs((now - st.st_birthtime) / DAY - age) < 0.1, path
    for name in ("OldLectureNotes.txt",):
        assert (uni / name).stat().st_size >= 2 * MIB
    for name in ("Assignment1.txt", "Assignment2.txt", "LectureNotes.txt"):
        assert (prev / name).stat().st_size >= 2 * MIB
    assert (prev / "RandomData.bin").stat().st_size == 2 * MIB
    assert (uni / "Résumé – 日本語.txt").is_file() and (uni / "notes with spaces.txt").is_file()
    text = estimate_compressibility(str(prev / "Assignment1.txt"))
    assert text["zip_ratio"] < 0.5 and text["estimated_zip_savings"] > MIB
    assert estimate_compressibility(str(prev / "RandomData.bin"))["estimated_zip_savings"] == 0


def test_refuses_non_empty_dir_unless_force(tmp_path: Path):
    (tmp_path / "keep.txt").write_text("mine")
    with pytest.raises(ValueError, match="not empty"):
        fixtures.generate(tmp_path, "benchmark", 3, 1, False)
    assert [p.name for p in tmp_path.iterdir()] == ["keep.txt"]
    fixtures.generate(tmp_path, "benchmark", 3, 1, True)
    assert (tmp_path / "keep.txt").read_text() == "mine"


def test_refuses_root_and_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    root = Path(tmp_path.anchor)
    with pytest.raises(ValueError, match="root or the home"):
        fixtures.generate(root, "mixed", 1, 1, True)
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path))
    with pytest.raises(ValueError, match="root or the home"):
        fixtures.generate(tmp_path, "mixed", 1, 1, True)
    fixtures.generate(tmp_path / "sub", "benchmark", 2, 1, False)  # a child of home is fine


def test_refuses_file_target(tmp_path: Path):
    f = tmp_path / "file"
    f.write_text("x")
    with pytest.raises(ValueError, match="not a directory"):
        fixtures.generate(f, "mixed", 1, 1, True)


def _snapshot(root: Path) -> dict[str, bytes]:
    return {str(p.relative_to(root)): p.read_bytes() for p in sorted(root.rglob("*")) if p.is_file()}


def test_benchmark_is_deterministic_and_nested(tmp_path: Path):
    now = time.time()
    a, b = tmp_path / "a", tmp_path / "b"
    fixtures.generate(a, "benchmark", 60, 7, False, now=now)
    fixtures.generate(b, "benchmark", 60, 7, False, now=now)
    snap_a = _snapshot(a)
    assert len(snap_a) == 60 and snap_a == _snapshot(b)
    assert any(len(Path(k).parts) > 2 for k in snap_a)
    c = tmp_path / "c"
    fixtures.generate(c, "benchmark", 60, 8, False, now=now)
    assert _snapshot(c) != snap_a
    ages = [(now - p.stat().st_mtime) / DAY for p in a.rglob("*") if p.is_file()]
    assert min(ages) < 14 < max(ages)


def test_cli(tmp_path: Path):
    out = tmp_path / "cli"
    env = {**os.environ, "PYTHONPATH": str(Path(__file__).parent.parent)}
    run = subprocess.run(
        [
            sys.executable,
            "-m",
            "idlestow.fixtures",
            "--out",
            str(out),
            "--preset",
            "benchmark",
            "--files",
            "5",
            "--seed",
            "3",
        ],
        capture_output=True,
        text=True,
        env=env,
        timeout=60,
    )
    assert run.returncode == 0, run.stderr
    assert json.loads(run.stdout)["files"] == 5
    again = subprocess.run(
        [sys.executable, "-m", "idlestow.fixtures", "--out", str(out)],
        capture_output=True,
        text=True,
        env=env,
        timeout=60,
    )
    assert again.returncode == 2 and "not empty" in again.stderr
