"""Streaming verification, extraction and inspection of IdleStow archives (zip and tar.zst).

Safety rules enforced here:

* names stored inside an archive are never used to build filesystem paths;
* only the data entry and the manifest are accepted (anything else is rejected);
* unsafe member names and non-regular tar members are rejected;
* decompression stops with ``LIMIT_EXCEEDED`` as soon as more than ``expected_size`` bytes appear;
* the manifest is capped at 64 KiB.
"""

from __future__ import annotations

import hashlib
import json
import os
import tarfile
import zipfile
import zlib
from dataclasses import dataclass
from typing import Any, BinaryIO, Protocol

from .. import _optional
from ..context import Context
from ..errors import Code, WorkerError
from ..fsutil import CHUNK_SIZE, fs_path, lexists, remove_quiet
from ..names import MANIFEST_MAX_BYTES, MANIFEST_NAME, is_unsafe_member_name, validate_entry_name

MAX_INSPECT_MEMBERS = 1000
MAX_LISTED_NAME_CHARS = 128
TAR_OVERHEAD_SLACK = 4 * 1024 * 1024  # tar headers, padding and pax records around the data
INSPECT_MAX_TAR_OUTPUT = 64 * 1024 * 1024 * 1024
_TAR_REGULAR = (tarfile.REGTYPE, tarfile.AREGTYPE)
_ZIP_METHODS = (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)


class Sink(Protocol):
    def begin(self) -> None: ...
    def write(self, data: bytes) -> None: ...


@dataclass
class ScanResult:
    sha256: str
    size: int
    manifest: dict[str, Any]


def _integrity(message: str) -> WorkerError:
    return WorkerError(Code.INTEGRITY_FAILURE, message)


def _require_format(fmt: str) -> None:
    if fmt == "zip":
        return
    if fmt == "tar.zst":
        if not _optional.zstd_available():
            raise WorkerError(Code.UNSUPPORTED_FORMAT, "tar.zst requires the 'zstandard' package")
        return
    raise WorkerError(Code.UNSUPPORTED_FORMAT, f"unsupported archive format: {fmt!r}")


def _parse_manifest(data: bytes) -> dict[str, Any]:
    try:
        manifest = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError, RecursionError) as exc:
        raise _integrity("manifest is not valid UTF-8 JSON") from exc
    if not isinstance(manifest, dict):
        raise _integrity("manifest is not a JSON object")
    return manifest


def _check_manifest(manifest: dict[str, Any], entry_name: str, size: int, sha256: str) -> None:
    if manifest.get("entry_name") != entry_name:
        raise _integrity("manifest entry_name mismatch")
    m_size = manifest.get("size")
    if type(m_size) is not int or m_size != size:
        raise _integrity("manifest size mismatch")
    m_sha = manifest.get("sha256")
    if not isinstance(m_sha, str) or m_sha.lower() != sha256.lower():
        raise _integrity("manifest sha256 mismatch")


def _stream_entry(reader: Any, expected_size: int, ctx: Context, sink: Sink | None) -> tuple[str, int]:
    """Hash (and optionally write) the data entry, never producing more than ``expected_size`` bytes."""
    digest = hashlib.sha256()
    total = 0
    if sink is not None:
        sink.begin()
    while True:
        ctx.check()
        chunk = reader.read(CHUNK_SIZE)
        if not chunk:
            break
        total += len(chunk)
        if total > expected_size:
            raise WorkerError(Code.LIMIT_EXCEEDED, "archive entry is larger than the expected size")
        digest.update(chunk)
        if sink is not None:
            sink.write(chunk)
        ctx.progress(total, expected_size, "verifying")
    return digest.hexdigest(), total


# ---------------------------------------------------------------------------------------------
# zip


def _check_zip_infos(infos: list[zipfile.ZipInfo], entry_name: str) -> None:
    for info in infos:
        if is_unsafe_member_name(info.orig_filename) or is_unsafe_member_name(info.filename):
            raise WorkerError(Code.UNSAFE_PATH, "archive contains an unsafe member name")
    names = [i.filename for i in infos]
    if len(names) != 2 or set(names) != {entry_name, MANIFEST_NAME}:
        raise _integrity("archive must contain exactly the data entry and the manifest")
    for info in infos:
        if info.flag_bits & 0x1:
            raise _integrity("encrypted archive members are not accepted")
        if info.compress_type not in _ZIP_METHODS:
            raise _integrity("unexpected zip compression method")


def _scan_zip(path: str, entry_name: str, expected_size: int, ctx: Context, sink: Sink | None) -> ScanResult:
    try:
        with open(fs_path(path), "rb") as raw, zipfile.ZipFile(raw) as zf:
            infos = zf.infolist()
            _check_zip_infos(infos, entry_name)
            by_name = {i.filename: i for i in infos}
            entry = by_name[entry_name]
            man = by_name[MANIFEST_NAME]
            if entry.file_size > expected_size:
                raise WorkerError(Code.LIMIT_EXCEEDED, "archive entry is larger than the expected size")
            if man.file_size > MANIFEST_MAX_BYTES:
                raise WorkerError(Code.LIMIT_EXCEEDED, "manifest exceeds 64 KiB")
            with zf.open(entry) as reader:
                sha, size = _stream_entry(reader, expected_size, ctx, sink)
            with zf.open(man) as mreader:
                mdata = mreader.read(MANIFEST_MAX_BYTES + 1)
            if len(mdata) > MANIFEST_MAX_BYTES:
                raise WorkerError(Code.LIMIT_EXCEEDED, "manifest exceeds 64 KiB")
            return ScanResult(sha, size, _parse_manifest(mdata))
    except (zipfile.BadZipFile, zlib.error, EOFError, NotImplementedError, RuntimeError) as exc:
        raise _integrity(f"corrupt zip archive: {exc}") from exc


# ---------------------------------------------------------------------------------------------
# tar.zst


class _GuardedReader:
    """Wraps the decompressed stream: cancellation per read and a hard output cap."""

    def __init__(self, raw: Any, ctx: Context, limit: int) -> None:
        self._raw = raw
        self._ctx = ctx
        self._limit = limit
        self._count = 0

    def read(self, n: int = -1) -> bytes:
        self._ctx.check()
        data = self._raw.read(CHUNK_SIZE if n is None or n < 0 else min(n, CHUNK_SIZE))
        self._count += len(data)
        if self._count > self._limit:
            raise WorkerError(Code.LIMIT_EXCEEDED, "decompressed tar stream exceeds the allowed size")
        return data


def _open_tar_stream(raw: BinaryIO, ctx: Context, limit: int) -> tuple[Any, tarfile.TarFile]:
    zstd = _optional.zstandard()
    assert zstd is not None  # noqa: S101 - guarded by _require_format
    reader = zstd.ZstdDecompressor().stream_reader(raw)
    tf = tarfile.open(fileobj=_GuardedReader(reader, ctx, limit), mode="r|", bufsize=CHUNK_SIZE)  # noqa: SIM115
    return reader, tf


def _tar_member_problem(member: tarfile.TarInfo) -> str | None:
    if is_unsafe_member_name(member.name):
        return "archive contains an unsafe member name"
    if member.type not in _TAR_REGULAR:
        return "archive contains a non-regular member (link, device, directory or sparse file)"
    return None


def _zstd_error_types() -> tuple[type[BaseException], ...]:
    zstd = _optional.zstandard()
    return (zstd.ZstdError,) if zstd is not None else ()


def _scan_tar(path: str, entry_name: str, expected_size: int, ctx: Context, sink: Sink | None) -> ScanResult:
    allowed = {entry_name, MANIFEST_NAME}
    seen: set[str] = set()
    result: tuple[str, int] | None = None
    manifest: dict[str, Any] | None = None
    try:
        with open(fs_path(path), "rb") as raw:
            reader, tf = _open_tar_stream(raw, ctx, expected_size + MANIFEST_MAX_BYTES + TAR_OVERHEAD_SLACK)
            with reader, tf:
                for member in tf:
                    problem = _tar_member_problem(member)
                    if problem:
                        raise WorkerError(Code.UNSAFE_PATH, problem)
                    if member.name not in allowed:
                        raise _integrity("archive contains an unexpected member")
                    if member.name in seen:
                        raise _integrity("archive contains a duplicate member")
                    seen.add(member.name)
                    fobj = tf.extractfile(member)
                    if fobj is None:
                        raise _integrity("archive member cannot be read")
                    if member.name == entry_name:
                        if member.size > expected_size:
                            raise WorkerError(Code.LIMIT_EXCEEDED, "archive entry is larger than expected")
                        result = _stream_entry(fobj, expected_size, ctx, sink)
                    else:
                        if member.size > MANIFEST_MAX_BYTES:
                            raise WorkerError(Code.LIMIT_EXCEEDED, "manifest exceeds 64 KiB")
                        manifest = _parse_manifest(fobj.read(MANIFEST_MAX_BYTES + 1))
    except (tarfile.TarError, EOFError, *_zstd_error_types()) as exc:
        raise _integrity(f"corrupt tar.zst archive: {exc}") from exc
    if result is None or manifest is None:
        raise _integrity("archive must contain exactly the data entry and the manifest")
    return ScanResult(result[0], result[1], manifest)


def _scan(
    path: str, fmt: str, entry_name: str, expected_size: int, ctx: Context, sink: Sink | None
) -> ScanResult:
    if fmt == "zip":
        return _scan_zip(path, entry_name, expected_size, ctx, sink)
    return _scan_tar(path, entry_name, expected_size, ctx, sink)


def _validate_expected(expected_sha256: str, expected_size: int) -> str:
    if len(expected_sha256) != 64 or any(c not in "0123456789abcdefABCDEF" for c in expected_sha256):
        raise WorkerError(Code.BAD_REQUEST, "expected_sha256 must be 64 hex characters")
    if type(expected_size) is not int or expected_size < 0:
        raise WorkerError(Code.BAD_REQUEST, "expected_size must be a non-negative integer")
    return expected_sha256.lower()


def _compare(scan: ScanResult, entry_name: str, expected_sha: str, expected_size: int) -> None:
    _check_manifest(scan.manifest, entry_name, expected_size, expected_sha)
    if scan.size != expected_size:
        raise _integrity(f"decompressed size {scan.size} != expected {expected_size}")
    if scan.sha256 != expected_sha:
        raise _integrity("decompressed sha256 does not match the expected value")


def verify_archive(
    archive: str,
    fmt: str,
    entry_name: str,
    expected_sha256: str,
    expected_size: int,
    ctx: Context | None = None,
) -> dict[str, Any]:
    """Fully decompress the data entry (never to disk) and compare it with the expectations."""
    ctx = ctx or Context()
    _require_format(fmt)
    validate_entry_name(entry_name)
    expected_sha = _validate_expected(expected_sha256, expected_size)
    scan = _scan(archive, fmt, entry_name, expected_size, ctx, None)
    _compare(scan, entry_name, expected_sha, expected_size)
    return {"ok": True, "sha256": scan.sha256, "size": scan.size, "manifest": scan.manifest}


class _FileSink:
    """Exclusive-create output file that is removed again if the extraction does not complete."""

    def __init__(self, dest: str) -> None:
        self._dest = dest
        self._file: BinaryIO | None = None
        self.created = False

    def begin(self) -> None:
        self._file = open(fs_path(self._dest), "xb")  # noqa: SIM115 - closed in commit()/abort()
        self.created = True

    def write(self, data: bytes) -> None:
        assert self._file is not None  # noqa: S101
        self._file.write(data)

    def commit(self) -> None:
        assert self._file is not None  # noqa: S101
        self._file.flush()
        os.fsync(self._file.fileno())
        self._file.close()
        self._file = None

    def abort(self) -> None:
        if self._file is not None:
            try:
                self._file.close()
            except OSError:
                pass
            self._file = None
        if self.created:
            remove_quiet(self._dest)


def extract_archive(
    archive: str,
    fmt: str,
    entry_name: str,
    dest: str,
    expected_sha256: str,
    expected_size: int,
    ctx: Context | None = None,
) -> dict[str, Any]:
    """Write the data entry to exactly ``dest`` (created exclusively), verifying size and hash."""
    ctx = ctx or Context()
    _require_format(fmt)
    validate_entry_name(entry_name)
    expected_sha = _validate_expected(expected_sha256, expected_size)
    if lexists(dest):
        raise WorkerError(Code.EXISTS, "destination already exists")
    sink = _FileSink(dest)
    ok = False
    try:
        scan = _scan(archive, fmt, entry_name, expected_size, ctx, sink)
        _compare(scan, entry_name, expected_sha, expected_size)
        sink.commit()
        ok = True
    finally:
        if not ok:
            sink.abort()
    return {"dest": dest, "size": scan.size, "sha256": scan.sha256}


# ---------------------------------------------------------------------------------------------
# inspect


def _listed(name: str, size: int, compressed: int, unsafe: bool) -> dict[str, Any]:
    shown = name if len(name) <= MAX_LISTED_NAME_CHARS else name[:MAX_LISTED_NAME_CHARS]
    return {"name": shown, "size": size, "compressed_size": compressed, "unsafe": unsafe}


def _inspect_zip(path: str, ctx: Context) -> dict[str, Any]:
    members: list[dict[str, Any]] = []
    manifest: dict[str, Any] | None = None
    try:
        with open(fs_path(path), "rb") as raw, zipfile.ZipFile(raw) as zf:
            for info in zf.infolist():
                ctx.check()
                unsafe = is_unsafe_member_name(info.orig_filename) or is_unsafe_member_name(info.filename)
                if len(members) < MAX_INSPECT_MEMBERS:
                    members.append(_listed(info.filename, info.file_size, info.compress_size, unsafe))
                if (
                    info.filename == MANIFEST_NAME
                    and manifest is None
                    and info.file_size <= MANIFEST_MAX_BYTES
                    and not info.flag_bits & 0x1
                ):
                    with zf.open(info) as mreader:
                        data = mreader.read(MANIFEST_MAX_BYTES + 1)
                    manifest = _try_manifest(data)
    except (zipfile.BadZipFile, zlib.error, EOFError, NotImplementedError, RuntimeError) as exc:
        raise _integrity(f"corrupt zip archive: {exc}") from exc
    return {"members": members, "manifest": manifest}


def _try_manifest(data: bytes) -> dict[str, Any] | None:
    if len(data) > MANIFEST_MAX_BYTES:
        return None
    try:
        return _parse_manifest(data)
    except WorkerError:
        return None


def _inspect_tar(path: str, ctx: Context) -> dict[str, Any]:
    members: list[dict[str, Any]] = []
    manifest: dict[str, Any] | None = None
    try:
        with open(fs_path(path), "rb") as raw:
            reader, tf = _open_tar_stream(raw, ctx, INSPECT_MAX_TAR_OUTPUT)
            with reader, tf:
                for member in tf:
                    unsafe = _tar_member_problem(member) is not None
                    # tar members are stored raw inside the zstd stream, so compressed_size == size here
                    members.append(_listed(member.name, member.size, member.size, unsafe))
                    if (
                        member.name == MANIFEST_NAME
                        and manifest is None
                        and not unsafe
                        and member.size <= MANIFEST_MAX_BYTES
                    ):
                        fobj = tf.extractfile(member)
                        if fobj is not None:
                            manifest = _try_manifest(fobj.read(MANIFEST_MAX_BYTES + 1))
                    if len(members) >= MAX_INSPECT_MEMBERS:
                        break
    except (tarfile.TarError, EOFError, *_zstd_error_types()) as exc:
        raise _integrity(f"corrupt tar.zst archive: {exc}") from exc
    return {"members": members, "manifest": manifest}


def inspect_archive(archive: str, fmt: str, ctx: Context | None = None) -> dict[str, Any]:
    """List up to 1000 members (flagging unsafe ones) and return the manifest if present and valid."""
    ctx = ctx or Context()
    _require_format(fmt)
    return _inspect_zip(archive, ctx) if fmt == "zip" else _inspect_tar(archive, ctx)
