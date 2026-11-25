from __future__ import annotations

import hashlib
import json
import os
import tarfile
import zipfile
from pathlib import Path
from typing import Any

import pytest
from conftest import FORMATS, HAVE_ZSTD, requires_zstd
from helpers import Harness, make_tar_zst, make_zip, reg

from idlestow import _optional
from idlestow.compression.archive import create_archive
from idlestow.context import CancelToken, Context
from idlestow.errors import WorkerError
from idlestow.names import MANIFEST_NAME
from idlestow.verification import archive as varchive
from idlestow.verification.archive import extract_archive, inspect_archive, verify_archive


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def code_of(excinfo: pytest.ExceptionInfo[WorkerError]) -> str:
    return excinfo.value.code


def manifest_bytes(name: str, data: bytes, **over: Any) -> bytes:
    m = {"format_version": 1, "entry_name": name, "size": len(data), "sha256": sha(data)}
    m.update(over)
    return json.dumps(m).encode()


def make(
    tmp_path: Path, fmt: str, data: bytes, name: str = "report.txt", **kw: Any
) -> tuple[Path, Path, dict]:
    src = tmp_path / name
    src.write_bytes(data)
    dest = tmp_path / ("out." + fmt.replace(".", "_"))
    res = create_archive(str(src), str(dest), fmt, kw.pop("level", "balanced"), name, **kw)
    return src, dest, res


COMPRESSIBLE = b"hello idlestow compressible text line\n" * 20000


@pytest.mark.parametrize("fmt", FORMATS)
@pytest.mark.parametrize("level", ["fast", "balanced", "maximum"])
def test_roundtrip_create_verify_extract(tmp_path: Path, fmt: str, level: str):
    src, dest, res = make(tmp_path, fmt, COMPRESSIBLE, level=level, host_manifest={"job": "j-1"})
    assert res["archive"] == str(dest) and res["format"] == fmt and res["entry_name"] == "report.txt"
    assert res["source_size"] == len(COMPRESSIBLE) and res["source_sha256"] == sha(COMPRESSIBLE)
    assert res["archive_size"] == dest.stat().st_size < len(COMPRESSIBLE)
    ver = verify_archive(str(dest), fmt, "report.txt", sha(COMPRESSIBLE), len(COMPRESSIBLE))
    assert ver["ok"] and ver["sha256"] == sha(COMPRESSIBLE) and ver["size"] == len(COMPRESSIBLE)
    man = ver["manifest"]
    assert man["format_version"] == 1 and man["entry_name"] == "report.txt" and man["job"] == "j-1"
    assert man["source_mtime_ns"] == src.stat().st_mtime_ns and man["tool"] == "idlestow-worker/0.1.1"
    assert man["created_utc"].endswith("Z")
    out = tmp_path / "restored.txt"
    ex = extract_archive(str(dest), fmt, "report.txt", str(out), sha(COMPRESSIBLE), len(COMPRESSIBLE))
    assert ex == {"dest": str(out), "size": len(COMPRESSIBLE), "sha256": sha(COMPRESSIBLE)}
    assert out.read_bytes() == COMPRESSIBLE


@pytest.mark.parametrize("fmt", FORMATS)
def test_archive_has_exactly_two_members_data_then_manifest(tmp_path: Path, fmt: str):
    _, dest, _ = make(tmp_path, fmt, COMPRESSIBLE)
    info = inspect_archive(str(dest), fmt)
    assert [m["name"] for m in info["members"]] == ["report.txt", MANIFEST_NAME]
    assert not any(m["unsafe"] for m in info["members"])
    assert info["members"][0]["size"] == len(COMPRESSIBLE)
    assert info["manifest"]["sha256"] == sha(COMPRESSIBLE)


@requires_zstd
def test_tar_members_are_plain_regular_files(tmp_path: Path):
    import zstandard

    _, dest, _ = make(tmp_path, "tar.zst", COMPRESSIBLE)
    with dest.open("rb") as raw, zstandard.ZstdDecompressor().stream_reader(raw) as r:
        with tarfile.open(fileobj=r, mode="r|") as tf:
            members = list(tf)
    assert [m.name for m in members] == ["report.txt", MANIFEST_NAME]
    for m in members:
        assert m.type == tarfile.REGTYPE and m.mode == 0o644 and m.uid == 0 and m.gid == 0
        assert m.uname == "" and m.gname == ""


def test_zip_uses_deflate_and_levels_differ(tmp_path: Path):
    _, dest, _ = make(tmp_path, "zip", COMPRESSIBLE, level="maximum")
    with zipfile.ZipFile(dest) as zf:
        assert all(i.compress_type == zipfile.ZIP_DEFLATED for i in zf.infolist())
        assert zf.testzip() is None


def test_zip_member_mtime_matches_source(tmp_path: Path):
    src = tmp_path / "old.txt"
    src.write_bytes(COMPRESSIBLE)
    os.utime(src, (1_600_000_000, 1_600_000_000))
    dest = tmp_path / "o.zip"
    create_archive(str(src), str(dest), "zip", "fast", "old.txt")
    with zipfile.ZipFile(dest) as zf:
        assert zf.infolist()[0].date_time[:3] == (2020, 9, 13)


@pytest.mark.parametrize("fmt", FORMATS)
def test_dest_exists_is_rejected_and_untouched(tmp_path: Path, fmt: str):
    src = tmp_path / "a.txt"
    src.write_bytes(COMPRESSIBLE)
    dest = tmp_path / "exists.out"
    dest.write_bytes(b"precious")
    with pytest.raises(WorkerError) as e:
        create_archive(str(src), str(dest), fmt, "fast", "a.txt")
    assert code_of(e) == "EXISTS" and dest.read_bytes() == b"precious"


def test_missing_source_and_parent(tmp_path: Path):
    with pytest.raises(FileNotFoundError):
        create_archive(str(tmp_path / "nope"), str(tmp_path / "o.zip"), "zip", "fast", "x")
    (tmp_path / "s.txt").write_bytes(COMPRESSIBLE)
    with pytest.raises(FileNotFoundError):
        create_archive(str(tmp_path / "s.txt"), str(tmp_path / "nodir" / "o.zip"), "zip", "fast", "s.txt")


@pytest.mark.parametrize("fmt", FORMATS)
def test_no_benefit_on_random_data_removes_archive(tmp_path: Path, fmt: str):
    src = tmp_path / "rand.bin"
    src.write_bytes(os.urandom(256 * 1024))
    dest = tmp_path / "rand.out"
    with pytest.raises(WorkerError) as e:
        create_archive(str(src), str(dest), fmt, "balanced", "rand.bin", fail_if_larger=True)
    assert code_of(e) == "NO_BENEFIT" and not dest.exists()
    create_archive(str(src), str(dest), fmt, "balanced", "rand.bin", fail_if_larger=False)
    assert dest.exists()


@pytest.mark.parametrize("fmt", FORMATS)
def test_source_changed_expected_sha_mismatch(tmp_path: Path, fmt: str):
    src = tmp_path / "a.txt"
    src.write_bytes(COMPRESSIBLE)
    dest = tmp_path / "a.out"
    with pytest.raises(WorkerError) as e:
        create_archive(str(src), str(dest), fmt, "fast", "a.txt", expected_sha256="0" * 64)
    assert code_of(e) == "INTEGRITY_FAILURE" and not dest.exists()
    create_archive(str(src), str(dest), fmt, "fast", "a.txt", expected_sha256=sha(COMPRESSIBLE).upper())
    assert dest.exists()


@pytest.mark.parametrize("fmt", FORMATS)
def test_cancel_during_create_removes_partial(tmp_path: Path, fmt: str):
    src = tmp_path / "big.txt"
    src.write_bytes(COMPRESSIBLE * 20)
    dest = tmp_path / "big.out"
    token = CancelToken()
    seen = []

    def on_progress(done, total, stage):
        seen.append(done)
        token.cancel()

    ctx = Context(token, on_progress, min_interval=0)
    with pytest.raises(WorkerError) as e:
        create_archive(str(src), str(dest), fmt, "fast", "big.txt", ctx=ctx)
    assert code_of(e) == "CANCELLED" and not dest.exists() and seen


@pytest.mark.parametrize(
    "bad", ["a/b.txt", "..", "a\\b", "C:evil.txt", "", "x\x00y", "x\ny", MANIFEST_NAME, "/abs"]
)
def test_bad_entry_names_rejected(tmp_path: Path, bad: str):
    src = tmp_path / "a.txt"
    src.write_bytes(COMPRESSIBLE)
    with pytest.raises(WorkerError) as e:
        create_archive(str(src), str(tmp_path / "o.zip"), "zip", "fast", bad)
    assert code_of(e) in ("UNSAFE_PATH", "BAD_REQUEST")
    assert not (tmp_path / "o.zip").exists()


@pytest.mark.parametrize("fmt", FORMATS)
def test_zero_byte_file(tmp_path: Path, fmt: str):
    src, dest, res = make(tmp_path, fmt, b"", name="empty.txt", fail_if_larger=False)
    assert res["source_size"] == 0 and res["source_sha256"] == sha(b"")
    verify_archive(str(dest), fmt, "empty.txt", sha(b""), 0)
    out = tmp_path / "empty.restored"
    extract_archive(str(dest), fmt, "empty.txt", str(out), sha(b""), 0)
    assert out.exists() and out.read_bytes() == b""
    src2 = tmp_path / "empty2.txt"
    src2.write_bytes(b"")
    with pytest.raises(WorkerError) as e:
        create_archive(str(src2), str(tmp_path / "e2.out"), fmt, "fast", "empty2.txt")
    assert code_of(e) == "NO_BENEFIT"


@pytest.mark.parametrize("fmt", FORMATS)
def test_unicode_and_spaces_names(tmp_path: Path, fmt: str):
    name = "Résumé – 日本語 notes.txt"
    src, dest, _ = make(tmp_path, fmt, COMPRESSIBLE, name=name)
    ver = verify_archive(str(dest), fmt, name, sha(COMPRESSIBLE), len(COMPRESSIBLE))
    assert ver["manifest"]["entry_name"] == name
    assert inspect_archive(str(dest), fmt)["members"][0]["name"] == name
    out = tmp_path / "wiederhergestellt – ü.txt"
    extract_archive(str(dest), fmt, name, str(out), sha(COMPRESSIBLE), len(COMPRESSIBLE))
    assert out.read_bytes() == COMPRESSIBLE


# ----------------------------------------------------------------------------- hostile zips

EVIL_NAMES = [
    "../evil.txt",
    "/abs/evil.txt",
    "C:/evil.txt",
    "C:\\evil.txt",
    "a/../../evil.txt",
    "dir\\..\\evil.txt",
]


@pytest.mark.parametrize("evil", EVIL_NAMES)
def test_zip_slip_names_flagged_and_never_extracted(tmp_path: Path, evil: str):
    data = b"payload" * 100
    arc = tmp_path / "evil.zip"
    make_zip(
        arc, [("report.txt", data), (evil, b"pwned"), (MANIFEST_NAME, manifest_bytes("report.txt", data))]
    )
    info = inspect_archive(str(arc), "zip")
    flagged = {m["name"]: m["unsafe"] for m in info["members"]}
    assert any(unsafe for unsafe in flagged.values())
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "zip", "report.txt", sha(data), len(data))
    assert code_of(e) == "UNSAFE_PATH"
    sandbox = tmp_path / "sandbox"
    sandbox.mkdir()
    dest = sandbox / "out.txt"
    with pytest.raises(WorkerError):
        extract_archive(str(arc), "zip", "report.txt", str(dest), sha(data), len(data))
    assert not dest.exists() and list(sandbox.iterdir()) == []
    assert not (tmp_path / "evil.txt").exists() and not (tmp_path.parent / "evil.txt").exists()


def test_zip_slip_as_entry_itself_flagged(tmp_path: Path):
    arc = tmp_path / "evil.zip"
    make_zip(arc, [("../evil.txt", b"x"), (MANIFEST_NAME, b"{}")])
    assert inspect_archive(str(arc), "zip")["members"][0]["unsafe"] is True
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "zip", "../evil.txt", sha(b"x"), 1)
    assert code_of(e) == "UNSAFE_PATH"  # entry_name itself is rejected


def test_zip_extra_member_rejected(tmp_path: Path):
    data = b"d" * 1000
    arc = tmp_path / "extra.zip"
    make_zip(
        arc, [("report.txt", data), ("other.txt", b"x"), (MANIFEST_NAME, manifest_bytes("report.txt", data))]
    )
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "zip", "report.txt", sha(data), len(data))
    assert code_of(e) == "INTEGRITY_FAILURE"


def test_zip_duplicate_names_rejected(tmp_path: Path):
    data = b"d" * 1000
    arc = tmp_path / "dup.zip"
    with pytest.warns(UserWarning):
        make_zip(arc, [("report.txt", data), ("report.txt", data)])
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "zip", "report.txt", sha(data), len(data))
    assert code_of(e) == "INTEGRITY_FAILURE"


def test_zip_bomb_guard_limit_exceeded_and_partial_removed(tmp_path: Path):
    bomb = b"\0" * (8 * 1024 * 1024)
    arc = tmp_path / "bomb.zip"
    make_zip(arc, [("report.txt", bomb), (MANIFEST_NAME, manifest_bytes("report.txt", b"small"))])
    assert arc.stat().st_size < 100_000  # tiny on disk, huge when expanded
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "zip", "report.txt", sha(b"small"), 5)
    assert code_of(e) == "LIMIT_EXCEEDED"
    dest = tmp_path / "out.txt"
    with pytest.raises(WorkerError) as e:
        extract_archive(str(arc), "zip", "report.txt", str(dest), sha(b"small"), 5)
    assert code_of(e) == "LIMIT_EXCEEDED" and not dest.exists()


def test_stream_guard_stops_endless_output():
    class Endless:
        def __init__(self) -> None:
            self.served = 0

        def read(self, n: int) -> bytes:
            self.served += n
            return b"\0" * n

    reader = Endless()
    with pytest.raises(WorkerError) as e:
        varchive._stream_entry(reader, 3 * 1024 * 1024, Context(), None)
    assert code_of(e) == "LIMIT_EXCEEDED" and reader.served <= 5 * 1024 * 1024


def test_extract_stops_writing_at_expected_size(tmp_path: Path):
    data = b"A" * 5000
    arc = tmp_path / "ok.zip"
    make_zip(arc, [("report.txt", data), (MANIFEST_NAME, manifest_bytes("report.txt", data))])
    dest = tmp_path / "o.txt"
    with pytest.raises(WorkerError) as e:
        extract_archive(str(arc), "zip", "report.txt", str(dest), sha(data), 4999)
    assert code_of(e) == "LIMIT_EXCEEDED" and not dest.exists()


def test_manifest_mismatch_is_integrity_failure(tmp_path: Path):
    data = b"d" * 3000
    for override in ({"sha256": "f" * 64}, {"size": 1}, {"entry_name": "other.txt"}):
        arc = tmp_path / "m.zip"
        arc.unlink(missing_ok=True)
        make_zip(arc, [("report.txt", data), (MANIFEST_NAME, manifest_bytes("report.txt", data, **override))])
        with pytest.raises(WorkerError) as e:
            verify_archive(str(arc), "zip", "report.txt", sha(data), len(data))
        assert code_of(e) == "INTEGRITY_FAILURE", override
        dest = tmp_path / "x.out"
        with pytest.raises(WorkerError):
            extract_archive(str(arc), "zip", "report.txt", str(dest), sha(data), len(data))
        assert not dest.exists()


def test_oversized_and_invalid_manifest(tmp_path: Path):
    data = b"d" * 3000
    arc = tmp_path / "m.zip"
    make_zip(arc, [("report.txt", data), (MANIFEST_NAME, b" " * (64 * 1024 + 1))])
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "zip", "report.txt", sha(data), len(data))
    assert code_of(e) == "LIMIT_EXCEEDED"
    assert inspect_archive(str(arc), "zip")["manifest"] is None
    arc.unlink()
    make_zip(arc, [("report.txt", data), (MANIFEST_NAME, b"not json")])
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "zip", "report.txt", sha(data), len(data))
    assert code_of(e) == "INTEGRITY_FAILURE"


@pytest.mark.parametrize("fmt", FORMATS)
def test_wrong_expectations_fail_verification(tmp_path: Path, fmt: str):
    _, dest, _ = make(tmp_path, fmt, COMPRESSIBLE)
    with pytest.raises(WorkerError) as e:
        verify_archive(str(dest), fmt, "report.txt", "0" * 64, len(COMPRESSIBLE))
    assert code_of(e) == "INTEGRITY_FAILURE"
    with pytest.raises(WorkerError) as e:
        verify_archive(str(dest), fmt, "report.txt", sha(COMPRESSIBLE), len(COMPRESSIBLE) + 1)
    assert code_of(e) == "INTEGRITY_FAILURE"
    out = tmp_path / "never.txt"
    with pytest.raises(WorkerError):
        extract_archive(str(dest), fmt, "report.txt", str(out), "0" * 64, len(COMPRESSIBLE))
    assert not out.exists()


@pytest.mark.parametrize("fmt", FORMATS)
def test_extract_refuses_existing_dest(tmp_path: Path, fmt: str):
    _, dest, _ = make(tmp_path, fmt, COMPRESSIBLE)
    out = tmp_path / "taken.txt"
    out.write_bytes(b"mine")
    with pytest.raises(WorkerError) as e:
        extract_archive(str(dest), fmt, "report.txt", str(out), sha(COMPRESSIBLE), len(COMPRESSIBLE))
    assert code_of(e) == "EXISTS" and out.read_bytes() == b"mine"


@pytest.mark.parametrize("fmt", FORMATS)
def test_corrupt_archive_is_integrity_failure(tmp_path: Path, fmt: str):
    _, dest, _ = make(tmp_path, fmt, COMPRESSIBLE)
    blob = bytearray(dest.read_bytes())
    for i in range(len(blob) // 3, len(blob) // 3 + 64):
        blob[i] ^= 0xFF
    dest.write_bytes(bytes(blob))
    out = tmp_path / "c.txt"
    with pytest.raises(WorkerError) as e:
        extract_archive(str(dest), fmt, "report.txt", str(out), sha(COMPRESSIBLE), len(COMPRESSIBLE))
    assert code_of(e) in ("INTEGRITY_FAILURE", "LIMIT_EXCEEDED") and not out.exists()
    garbage = tmp_path / "garbage"
    garbage.write_bytes(b"this is not an archive" * 10)
    with pytest.raises(WorkerError) as e:
        verify_archive(str(garbage), fmt, "report.txt", sha(b"x"), 1)
    assert code_of(e) == "INTEGRITY_FAILURE"


# ----------------------------------------------------------------------------- hostile tars


@requires_zstd
def test_tar_symlink_member_rejected(tmp_path: Path):
    data = b"payload" * 100
    link = tarfile.TarInfo("report.txt")
    link.type = tarfile.SYMTYPE
    link.linkname = "/etc/passwd"
    arc = tmp_path / "evil.tar.zst"
    make_tar_zst(arc, [(link, b""), reg(MANIFEST_NAME, manifest_bytes("report.txt", data))])
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "tar.zst", "report.txt", sha(data), len(data))
    assert code_of(e) == "UNSAFE_PATH"
    info = inspect_archive(str(arc), "tar.zst")
    assert info["members"][0]["unsafe"] is True
    out = tmp_path / "o.txt"
    with pytest.raises(WorkerError):
        extract_archive(str(arc), "tar.zst", "report.txt", str(out), sha(data), len(data))
    assert not out.exists()


@requires_zstd
@pytest.mark.parametrize(
    "kind", [tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.BLKTYPE, tarfile.FIFOTYPE, tarfile.DIRTYPE]
)
def test_tar_other_special_members_rejected(tmp_path: Path, kind: bytes):
    data = b"payload" * 100
    special = tarfile.TarInfo("report.txt")
    special.type = kind
    if kind == tarfile.LNKTYPE:
        special.linkname = "somewhere"
    arc = tmp_path / "evil.tar.zst"
    make_tar_zst(arc, [(special, b""), reg(MANIFEST_NAME, manifest_bytes("report.txt", data))])
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "tar.zst", "report.txt", sha(data), len(data))
    assert code_of(e) == "UNSAFE_PATH"


@requires_zstd
@pytest.mark.parametrize("evil", ["../evil.txt", "/abs/evil.txt", "C:/evil.txt"])
def test_tar_unsafe_names_rejected(tmp_path: Path, evil: str):
    data = b"payload" * 100
    arc = tmp_path / "evil.tar.zst"
    make_tar_zst(
        arc,
        [reg("report.txt", data), reg(evil, b"x"), reg(MANIFEST_NAME, manifest_bytes("report.txt", data))],
    )
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "tar.zst", "report.txt", sha(data), len(data))
    assert code_of(e) == "UNSAFE_PATH"
    flagged = [m for m in inspect_archive(str(arc), "tar.zst")["members"] if m["unsafe"]]
    assert flagged and flagged[0]["name"] == evil


@requires_zstd
def test_tar_bomb_guard_and_extra_member(tmp_path: Path):
    arc = tmp_path / "bomb.tar.zst"
    make_tar_zst(arc, [reg("report.txt", b"\0" * (8 * 1024 * 1024)), reg(MANIFEST_NAME, b"{}")])
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "tar.zst", "report.txt", sha(b"x"), 10)
    assert code_of(e) == "LIMIT_EXCEEDED"
    data = b"d" * 100
    arc2 = tmp_path / "extra.tar.zst"
    make_tar_zst(
        arc2,
        [
            reg("report.txt", data),
            reg("sneaky.txt", b"x"),
            reg(MANIFEST_NAME, manifest_bytes("report.txt", data)),
        ],
    )
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc2), "tar.zst", "report.txt", sha(data), len(data))
    assert code_of(e) == "INTEGRITY_FAILURE"


@requires_zstd
def test_tar_missing_manifest(tmp_path: Path):
    data = b"d" * 100
    arc = tmp_path / "nomanifest.tar.zst"
    make_tar_zst(arc, [reg("report.txt", data)])
    with pytest.raises(WorkerError) as e:
        verify_archive(str(arc), "tar.zst", "report.txt", sha(data), len(data))
    assert code_of(e) == "INTEGRITY_FAILURE"


def test_inspect_lists_at_most_1000_members(tmp_path: Path):
    arc = tmp_path / "many.zip"
    with zipfile.ZipFile(arc, "w") as zf:
        for i in range(1200):
            zf.writestr(f"f{i}.txt", b"x")
    assert len(inspect_archive(str(arc), "zip")["members"]) == 1000


# ----------------------------------------------------------------------------- availability


def test_tar_zst_unavailable_is_unsupported_format(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(_optional, "zstandard", lambda: None)
    src = tmp_path / "a.txt"
    src.write_bytes(COMPRESSIBLE)
    with pytest.raises(WorkerError) as e:
        create_archive(str(src), str(tmp_path / "o.tar.zst"), "tar.zst", "fast", "a.txt")
    assert code_of(e) == "UNSUPPORTED_FORMAT" and not (tmp_path / "o.tar.zst").exists()
    with pytest.raises(WorkerError) as e:
        verify_archive(str(src), "tar.zst", "a.txt", "0" * 64, 1)
    assert code_of(e) == "UNSUPPORTED_FORMAT"
    h = Harness()
    caps = h.call("capabilities")["result"]
    assert caps["formats"] == ["zip"] and caps["zstd"] is False
    h.finish()


def test_unknown_format(tmp_path: Path):
    with pytest.raises(WorkerError) as e:
        inspect_archive(str(tmp_path / "x"), "rar")
    assert code_of(e) == "UNSUPPORTED_FORMAT"


def test_zstd_flag_matches_environment():
    assert _optional.zstd_available() == HAVE_ZSTD


# ----------------------------------------------------------------------------- over the protocol


@pytest.mark.parametrize("fmt", FORMATS)
def test_ops_happy_path_over_protocol(harness: Harness, tmp_path: Path, fmt: str):
    src = tmp_path / "doc.txt"
    src.write_bytes(COMPRESSIBLE)
    arc = tmp_path / "doc.arc"
    created = harness.call(
        "create_archive",
        {
            "source": str(src),
            "dest": str(arc),
            "format": fmt,
            "level": "fast",
            "entry_name": "doc.txt",
            "expected_sha256": sha(COMPRESSIBLE),
            "fail_if_larger": True,
            "manifest": {"k": "v"},
        },
        rid="c",
    )["result"]
    assert created["archive"] == str(arc) and created["source_sha256"] == sha(COMPRESSIBLE)
    exists = harness.call(
        "create_archive",
        {"source": str(src), "dest": str(arc), "format": fmt, "level": "fast", "entry_name": "doc.txt"},
        rid="c2",
    )
    assert exists["error"]["code"] == "EXISTS"
    ver = harness.call(
        "verify_archive",
        {
            "archive": str(arc),
            "format": fmt,
            "entry_name": "doc.txt",
            "expected_sha256": sha(COMPRESSIBLE),
            "expected_size": len(COMPRESSIBLE),
        },
        rid="v",
    )["result"]
    assert ver["ok"] is True and ver["manifest"]["k"] == "v"
    out = tmp_path / "back.txt"
    ex = harness.call(
        "extract_archive",
        {
            "archive": str(arc),
            "format": fmt,
            "entry_name": "doc.txt",
            "dest": str(out),
            "expected_sha256": sha(COMPRESSIBLE),
            "expected_size": len(COMPRESSIBLE),
        },
        rid="e",
    )["result"]
    assert ex["size"] == len(COMPRESSIBLE) and out.read_bytes() == COMPRESSIBLE
    ins = harness.call("inspect_archive", {"archive": str(arc), "format": fmt}, rid="i")["result"]
    assert len(ins["members"]) == 2 and ins["manifest"]["entry_name"] == "doc.txt"
    bad = harness.call("create_archive", {"source": str(src)}, rid="bad")
    assert bad["error"]["code"] == "BAD_REQUEST"
    badfmt = harness.call(
        "create_archive",
        {
            "source": str(src),
            "dest": str(tmp_path / "z"),
            "format": "rar",
            "level": "fast",
            "entry_name": "doc.txt",
        },
        rid="bf",
    )
    assert badfmt["error"]["code"] == "UNSUPPORTED_FORMAT"
