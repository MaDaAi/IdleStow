from __future__ import annotations

import hashlib
import io
import json
import os
import threading
from pathlib import Path

import pytest
from helpers import Harness

from idlestow.context import CancelToken, Context
from idlestow.errors import WorkerError
from idlestow.ipc.framing import MAX_LINE_BYTES, LineKind, MessageWriter, read_line
from idlestow.verification import hashing


def test_read_line_basic_and_crlf():
    s = io.BytesIO(b'{"a":1}\r\nsecond\nlast-no-newline')
    assert read_line(s) == (LineKind.LINE, b'{"a":1}')
    assert read_line(s) == (LineKind.LINE, b"second")
    assert read_line(s) == (LineKind.LINE, b"last-no-newline")
    assert read_line(s)[0] is LineKind.EOF


def test_read_line_oversize_is_discarded_and_stream_resyncs():
    big = b"x" * (MAX_LINE_BYTES + 5000)
    s = io.BytesIO(big + b"\nnext\n" + b"y" * MAX_LINE_BYTES + b"\nafter\n")
    assert read_line(s)[0] is LineKind.OVERSIZE
    assert read_line(s) == (LineKind.LINE, b"next")
    assert read_line(s) == (LineKind.LINE, b"y" * MAX_LINE_BYTES)  # exactly the limit is accepted
    assert read_line(s) == (LineKind.LINE, b"after")


def test_read_line_oversize_without_newline_at_eof():
    s = io.BytesIO(b"z" * (MAX_LINE_BYTES * 2))
    assert read_line(s)[0] is LineKind.OVERSIZE
    assert read_line(s)[0] is LineKind.EOF


def test_oversize_request_answers_message_too_large(harness: Harness):
    harness.raw(b"q" * (MAX_LINE_BYTES + 10) + b"\n")
    err = harness.wait(lambda m: m.get("type") == "error")
    assert err["id"] is None and err["error"]["code"] == "MESSAGE_TOO_LARGE"
    assert harness.call("ping", rid="after")["result"]["pong"] is True


def test_handshake_is_first_message(harness: Harness):
    ready = harness.wait(lambda m: m.get("type") == "ready")
    assert ready["worker"] == "idlestow-worker" and ready["protocol"] == 1 and ready["v"] == 1
    assert harness.out.messages()[0]["type"] == "ready"
    assert ready["capabilities"]["formats"][0] == "zip"
    assert isinstance(ready["pid"], int)


def test_bad_json_gets_null_id(harness: Harness):
    harness.raw(b"{not json\n")
    err = harness.wait(lambda m: m.get("type") == "error")
    assert err["id"] is None and err["error"]["code"] == "BAD_REQUEST"


def test_non_object_and_invalid_utf8(harness: Harness):
    harness.raw(b"[1,2,3]\n")
    harness.raw(bytes([0xFF, 0xFE, 0xFA, 10]))
    harness.wait(lambda m: len([x for x in harness.out.messages() if x.get("type") == "error"]) >= 2)
    errors = [m for m in harness.out.messages() if m.get("type") == "error"]
    assert all(e["id"] is None and e["error"]["code"] == "BAD_REQUEST" for e in errors)


@pytest.mark.parametrize("bad_id", [None, 5, "", "x" * 65, "has space", "sl/ash", ["a"]])
def test_bad_id(harness: Harness, bad_id):
    harness.raw(json.dumps({"v": 1, "id": bad_id, "op": "ping"}).encode() + b"\n")
    err = harness.wait(lambda m: m.get("type") == "error")
    assert err["id"] is None and err["error"]["code"] == "BAD_REQUEST"


def test_missing_id(harness: Harness):
    harness.raw(b'{"v":1,"op":"ping"}\n')
    err = harness.wait(lambda m: m.get("type") == "error")
    assert err["id"] is None and err["error"]["code"] == "BAD_REQUEST"


@pytest.mark.parametrize("v", [2, 0, "1", None, True, 1.0])
def test_bad_version(harness: Harness, v):
    if v is None:
        harness.raw(b'{"id":"a","op":"ping"}\n')
    else:
        harness.raw(json.dumps({"v": v, "id": "a", "op": "ping"}).encode() + b"\n")
    err = harness.terminal("a")
    assert err["error"]["code"] == "UNSUPPORTED_VERSION"


def test_unknown_op_and_bad_params(harness: Harness):
    assert harness.call("nope", rid="a")["error"]["code"] == "UNKNOWN_OP"
    harness.raw(b'{"v":1,"id":"b","op":"ping","params":[1]}\n')
    assert harness.terminal("b")["error"]["code"] == "BAD_REQUEST"
    harness.raw(b'{"v":1,"id":"c","op":5}\n')
    assert harness.terminal("c")["error"]["code"] == "UNKNOWN_OP"


def test_unknown_param_keys_ignored(harness: Harness):
    assert harness.call("ping", {"whatever": 1})["result"]["pong"] is True


ABS = "C:\\" if os.name == "nt" else "/"


@pytest.mark.parametrize(
    "params,code",
    [
        ({}, "BAD_REQUEST"),
        ({"path": 5}, "BAD_REQUEST"),
        ({"path": "relative/file.txt"}, "UNSAFE_PATH"),
        ({"path": ABS + "a\0b"}, "UNSAFE_PATH"),
        ({"path": ABS + "a" * 32001}, "UNSAFE_PATH"),
    ],
)
def test_path_validation(harness: Harness, params, code):
    assert harness.call("hash_file", params)["error"]["code"] == code


def test_ping_capabilities_and_not_found(harness: Harness, tmp_path: Path):
    pong = harness.call("ping")["result"]
    assert pong["pong"] is True and pong["time_ms"] > 1_600_000_000_000
    caps = harness.call("capabilities", rid="c")["result"]
    assert set(caps) == {"formats", "zstd", "psutil"}
    err = harness.call("hash_file", {"path": str(tmp_path / "missing.bin")}, rid="n")["error"]
    assert err["code"] == "NOT_FOUND" and err["retryable"] is False


def test_hash_file_happy_path(harness: Harness, text_file: Path):
    res = harness.call("hash_file", {"path": str(text_file)})["result"]
    assert res == {
        "sha256": hashlib.sha256(text_file.read_bytes()).hexdigest(),
        "size": text_file.stat().st_size,
    }


def test_duplicate_in_flight_id_rejected():
    gate = threading.Event()

    def slow(params, ctx):
        gate.wait(10)
        return {"ok": True}

    h = Harness(work_ops={"slow": slow})
    h.send("slow", rid="dup")
    h.send("slow", rid="dup")
    err = h.wait(lambda m: m.get("type") == "error")
    assert err["id"] == "dup" and err["error"]["code"] == "BAD_REQUEST"
    gate.set()
    assert h.wait(lambda m: m.get("id") == "dup" and m.get("type") == "result")
    h.finish()


def test_queue_cap_16_then_busy():
    started = threading.Event()
    gate = threading.Event()

    def slow(params, ctx):
        started.set()
        gate.wait(20)
        return {"n": params.get("n")}

    h = Harness(work_ops={"slow": slow})
    h.send("slow", {"n": 0}, rid="w0")
    assert started.wait(5)
    for i in range(1, 17):
        h.send("slow", {"n": i}, rid=f"w{i}")
    h.send("slow", {"n": 99}, rid="overflow")
    busy = h.terminal("overflow")
    assert busy["error"]["code"] == "BUSY" and busy["error"]["retryable"] is True
    gate.set()
    for i in range(17):
        assert h.terminal(f"w{i}")["result"]["n"] == i  # FIFO, all answered
    order = [m["id"] for m in h.out.messages() if m.get("type") == "result"]
    assert order == [f"w{i}" for i in range(17)]
    h.finish()


def test_control_ops_answered_while_work_runs():
    gate = threading.Event()
    started = threading.Event()

    def slow(params, ctx):
        started.set()
        gate.wait(20)
        return {}

    h = Harness(work_ops={"slow": slow})
    h.send("slow", rid="long")
    assert started.wait(5)
    assert h.call("ping", rid="p")["result"]["pong"] is True
    gate.set()
    h.terminal("long")
    h.finish()


def test_cancel_mid_hash_of_large_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    big = tmp_path / "big.bin"
    with big.open("wb") as f:
        f.truncate(64 * 1024 * 1024)  # 64 MiB of zeros, created instantly

    real = hashlib.sha256

    class SlowSha:
        def __init__(self):
            self._h = real()

        def update(self, data):
            import time

            time.sleep(0.01)
            self._h.update(data)

        def hexdigest(self):
            return self._h.hexdigest()

    monkeypatch.setattr(hashing.hashlib, "sha256", SlowSha)
    h = Harness()
    h.send("hash_file", {"path": str(big)}, rid="h1")
    h.wait(lambda m: m.get("id") == "h1" and m.get("type") == "progress")
    assert h.call("cancel", {"target": "h1"}, rid="c1")["result"] == {"cancelled": True}
    final = h.terminal("h1")
    assert final["type"] == "error" and final["error"]["code"] == "CANCELLED"
    assert h.call("cancel", {"target": "h1"}, rid="c2")["result"] == {"cancelled": False}
    h.finish()


def test_cancel_queued_job_never_runs():
    gate = threading.Event()
    started = threading.Event()
    ran = []

    def slow(params, ctx):
        started.set()
        gate.wait(20)
        ran.append(params["n"])
        return {}

    h = Harness(work_ops={"slow": slow})
    h.send("slow", {"n": 1}, rid="a")
    assert started.wait(5)
    h.send("slow", {"n": 2}, rid="b")
    assert h.call("cancel", {"target": "b"}, rid="c")["result"]["cancelled"] is True
    gate.set()
    assert h.terminal("b")["error"]["code"] == "CANCELLED"
    assert h.terminal("a")["type"] == "result"
    assert ran == [1]
    h.finish()


def test_cancel_requires_target(harness: Harness):
    assert harness.call("cancel", {})["error"]["code"] == "BAD_REQUEST"


def test_progress_is_rate_limited_and_precedes_terminal(tmp_path: Path):
    big = tmp_path / "big.bin"
    with big.open("wb") as f:
        f.truncate(256 * 1024 * 1024)
    h = Harness()
    h.send("hash_file", {"path": str(big)}, rid="p1")
    h.terminal("p1")
    msgs = [m for m in h.out.messages() if m.get("id") == "p1"]
    assert msgs[-1]["type"] == "result"
    progress = [m for m in msgs if m["type"] == "progress"]
    assert 1 <= len(progress) <= 8
    assert all(m["unit"] == "bytes" and m["stage"] == "hashing" for m in progress)
    h.finish()


def test_context_progress_min_interval():
    calls = []
    ctx = Context(CancelToken(), lambda d, t, s: calls.append(d), min_interval=10.0)
    for i in range(100):
        ctx.progress(i, 100, "x")
    assert calls == [0]


def test_shutdown_replies_then_exits_zero():
    h = Harness()
    assert h.call("shutdown", rid="s")["result"] == {"bye": True}
    h._thread.join(10)
    assert h.exit_code == 0


def test_shutdown_cancels_running_work():
    started = threading.Event()

    def spin(params, ctx):
        started.set()
        while True:
            ctx.check()
            threading.Event().wait(0.01)

    h = Harness(work_ops={"spin": spin})
    h.send("spin", rid="x")
    assert started.wait(5)
    h.send("shutdown", rid="s")
    assert h.terminal("x")["error"]["code"] == "CANCELLED"
    h._thread.join(10)
    assert h.exit_code == 0


def test_eof_exits_zero():
    h = Harness()
    h.finish()
    assert h.exit_code == 0


def test_internal_error_does_not_kill_worker():
    def boom(params, ctx):
        raise RuntimeError("bug")

    h = Harness(work_ops={"boom": boom})
    assert h.call("boom", rid="b")["error"]["code"] == "INTERNAL"
    assert h.call("ping", rid="p")["result"]["pong"] is True
    h.finish()


def test_oversized_result_becomes_error():
    out = io.BytesIO()
    w = MessageWriter(out)
    assert w.send({"v": 1, "id": "a", "type": "result", "result": {"blob": "x" * (MAX_LINE_BYTES + 10)}})
    msg = json.loads(out.getvalue())
    assert msg["type"] == "error" and msg["error"]["code"] == "LIMIT_EXCEEDED"


def test_worker_error_is_reported_with_retryable():
    def failing(params, ctx):
        raise WorkerError("IO_ERROR", "transient", retryable=True)

    h = Harness(work_ops={"failing": failing})
    err = h.call("failing")["error"]
    assert err == {"code": "IO_ERROR", "message": "transient", "retryable": True}
    h.finish()
