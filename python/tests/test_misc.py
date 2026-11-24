from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

from idlestow import _optional, monitoring
from idlestow.errors import WorkerError, from_os_error
from idlestow.fsutil import fs_path, is_absolute, validate_abs_path

PYDIR = Path(__file__).parent.parent
BS = chr(92)  # backslash
PREFIX = BS * 2 + "?" + BS


def test_fs_path_long_path_prefix():
    short = "C:" + BS + "a" + BS + "b.txt"
    assert fs_path(short, windows=True) == short
    long = "C:" + BS + BS.join(["d" * 50] * 6) + BS + "f.txt"
    assert len(long) > 240
    assert fs_path(long, windows=True) == PREFIX + long
    assert fs_path(PREFIX + long, windows=True) == PREFIX + long
    tail = BS.join(["d" * 50] * 6)
    unc = BS * 2 + "server" + BS + "share" + BS + tail
    assert fs_path(unc, windows=True) == PREFIX + "UNC" + BS + "server" + BS + "share" + BS + tail
    assert fs_path(long, windows=False) == long


@pytest.mark.skipif(os.name != "nt", reason="Windows long paths")
def test_long_path_actually_usable(tmp_path: Path):
    from idlestow.verification import hash_file

    deep = str(tmp_path)
    while len(deep) < 300:
        deep += BS + "n" * 60
    os.makedirs(fs_path(deep))
    target = deep + BS + "file.txt"
    with open(fs_path(target), "wb") as f:
        f.write(b"abc")
    assert hash_file(target)[1] == 3


def test_is_absolute():
    assert is_absolute("C:" + BS + "x", windows=True) and is_absolute("C:/x", windows=True)
    assert is_absolute(BS * 2 + "srv" + BS + "share" + BS + "x", windows=True)
    assert not is_absolute(BS + "x", windows=True) and not is_absolute("C:x", windows=True)
    assert not is_absolute("rel" + BS + "x", windows=True)
    assert is_absolute("/x", windows=False) and not is_absolute("x", windows=False)


def test_validate_abs_path_rejects():
    for bad in ("", "rel", "x" + chr(0) + "y"):
        with pytest.raises(WorkerError):
            validate_abs_path(bad, "p")


def test_error_mapping():
    assert from_os_error(FileNotFoundError(2, "x")).code == "NOT_FOUND"
    assert from_os_error(FileExistsError(17, "x")).code == "EXISTS"
    assert from_os_error(PermissionError(13, "x")).code == "PERMISSION_DENIED"
    assert from_os_error(OSError(28, "no space")).code == "IO_ERROR"
    assert from_os_error(OSError(16, "busy")).retryable is True


def test_system_status_without_psutil(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(_optional, "psutil", lambda: None)
    assert monitoring.system_status() == {"cpu_percent": None, "battery": None, "source": "none"}


def test_system_status_shape():
    res = monitoring.system_status()
    assert res["source"] in ("psutil", "none")
    if res["source"] == "psutil":
        assert res["cpu_percent"] is None or 0 <= res["cpu_percent"] <= 100
        assert res["battery"] is None or set(res["battery"]) == {"percent", "plugged"}


def test_system_status_over_protocol(harness):
    res = harness.call("system_status")["result"]
    assert set(res) == {"cpu_percent", "battery", "source"}


def test_benchmark_cli_reports_real_numbers():
    run = subprocess.run(
        [sys.executable, "-m", "idlestow.benchmarking", "--size-mib", "2", "--repeat", "1"],
        capture_output=True,
        text=True,
        cwd=PYDIR,
        timeout=180,
    )
    assert run.returncode == 0, run.stderr
    report = json.loads(run.stdout)
    assert report["python"] and report["platform"] and report["dataset"]["size_bytes_each"] == 2 * 1024 * 1024
    for res in report["results"].values():
        assert res["hash"]["bytes_processed"] == 2 * 1024 * 1024
        assert res["zip_create"]["mib_per_s_best"] > 0 and res["zip_verify"]["seconds_best"] > 0
        assert "estimate" in res


class _Reader:
    def __init__(self, stream):
        self.lines: list[str] = []
        self._t = threading.Thread(target=self._run, args=(stream,), daemon=True)
        self._t.start()

    def _run(self, stream):
        for line in stream:
            self.lines.append(line)

    def next(self, timeout=30):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if self.lines:
                return json.loads(self.lines.pop(0))
            time.sleep(0.01)
        raise AssertionError("timeout waiting for worker output")


def _spawn():
    env = {**os.environ, "PYTHONIOENCODING": "utf-8", "PYTHONPATH": str(PYDIR)}
    return subprocess.Popen(
        [sys.executable, "-u", "-B", "-s", "-X", "utf8", "-m", "idlestow.worker"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        cwd=PYDIR,
    )


def _send(proc, **msg):
    proc.stdin.write(json.dumps({"v": 1, **msg}).encode() + b"\n")
    proc.stdin.flush()


def test_end_to_end_subprocess(tmp_path: Path):
    f = tmp_path / "e2e.txt"
    f.write_bytes(b"abc" * 1000)
    proc = _spawn()
    try:
        out = _Reader(proc.stdout)
        ready = out.next()
        assert ready["type"] == "ready" and ready["protocol"] == 1 and ready["worker"] == "idlestow-worker"
        _send(proc, id="p1", op="ping", params={})
        assert out.next()["result"]["pong"] is True
        _send(proc, id="h1", op="hash_file", params={"path": str(f)})
        msg = out.next()
        while msg["type"] == "progress":
            msg = out.next()
        assert msg["id"] == "h1" and msg["result"]["sha256"] == hashlib.sha256(f.read_bytes()).hexdigest()
        _send(proc, id="s1", op="shutdown", params={})
        assert out.next()["result"] == {"bye": True}
        assert proc.wait(timeout=15) == 0
    finally:
        if proc.poll() is None:
            proc.kill()
        proc.stdin.close()
        proc.stdout.close()
        proc.stderr.close()


def test_end_to_end_eof_exits_zero_with_clean_stdout():
    proc = _spawn()
    try:
        proc.stdin.close()
        stdout = proc.stdout.read()
        assert proc.wait(timeout=15) == 0
        lines = [json.loads(line) for line in stdout.splitlines()]
        assert len(lines) == 1 and lines[0]["type"] == "ready"
    finally:
        if proc.poll() is None:
            proc.kill()
        proc.stdout.close()
        proc.stderr.close()
