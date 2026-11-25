"""Archive creation (zip and tar.zst) with streaming, exclusive output and cleanup on failure."""

from __future__ import annotations

import hashlib
import json
import os
import stat
import time
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any, BinaryIO

from .. import TOOL_ID, _optional
from ..context import Context
from ..errors import Code, WorkerError
from ..fsutil import CHUNK_SIZE, fs_path, lexists, remove_quiet
from ..names import MANIFEST_MAX_BYTES, MANIFEST_NAME, validate_entry_name
from .levels import FORMATS, LEVEL_NAMES, ZIP_LEVELS, ZSTD_LEVELS

if TYPE_CHECKING:
    import tarfile

_ZIP64_LIMIT = (1 << 31) - 1
_DOS_MIN = (1980, 1, 1, 0, 0, 0)
_DOS_MAX_YEAR = 2107


class _HashingReader:
    """read(n) over the source: hashes and counts exactly what is archived, cancels, reports progress."""

    def __init__(self, handle: BinaryIO, total: int, ctx: Context) -> None:
        self._handle = handle
        self._total = total
        self._ctx = ctx
        self.digest = hashlib.sha256()
        self.count = 0

    def read(self, n: int = -1) -> bytes:
        self._ctx.check()
        data = self._handle.read(CHUNK_SIZE if n is None or n < 0 else min(n, CHUNK_SIZE))
        if data:
            self.digest.update(data)
            self.count += len(data)
            self._ctx.progress(self.count, self._total, "compressing")
        return data


def _dos_time(mtime: float) -> tuple[int, int, int, int, int, int]:
    t = time.gmtime(max(mtime, 0))
    if t.tm_year < 1980:
        return _DOS_MIN
    if t.tm_year > _DOS_MAX_YEAR:
        return (_DOS_MAX_YEAR, 12, 31, 23, 59, 58)
    return (t.tm_year, t.tm_mon, t.tm_mday, t.tm_hour, t.tm_min, t.tm_sec)


def _build_manifest(entry_name: str, size: int, sha256: str, mtime_ns: int, host: dict[str, Any]) -> bytes:
    manifest: dict[str, Any] = dict(host)
    manifest.update(
        {
            "format_version": 1,
            "entry_name": entry_name,
            "size": size,
            "sha256": sha256,
            "source_mtime_ns": mtime_ns,
            "created_utc": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "tool": TOOL_ID,
        }
    )
    data = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(data) > MANIFEST_MAX_BYTES:
        raise WorkerError(Code.BAD_REQUEST, "manifest would exceed 64 KiB")
    return data


def _write_zip(
    out: BinaryIO,
    reader: _HashingReader,
    entry_name: str,
    size: int,
    mtime: float,
    level: int,
    finish_manifest: Any,
) -> None:
    import zipfile  # lazy: keeps worker start-up fast

    with zipfile.ZipFile(
        out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=level, allowZip64=True
    ) as zf:
        info = zipfile.ZipInfo(entry_name, date_time=_dos_time(mtime))
        info.compress_type = zipfile.ZIP_DEFLATED
        info._compresslevel = level  # noqa: SLF001 - the only way to set the level for a streamed member
        info.create_system = 3
        info.external_attr = (stat.S_IFREG | 0o644) << 16
        info.file_size = size
        with zf.open(info, "w", force_zip64=size * 1.05 > _ZIP64_LIMIT) as member:
            while chunk := reader.read(CHUNK_SIZE):
                member.write(chunk)
        manifest = finish_manifest()
        minfo = zipfile.ZipInfo(MANIFEST_NAME, date_time=_dos_time(time.time()))
        minfo.create_system = 3
        minfo.external_attr = (stat.S_IFREG | 0o644) << 16
        zf.writestr(minfo, manifest, zipfile.ZIP_DEFLATED, level)


def _tar_info(name: str, size: int, mtime: float) -> tarfile.TarInfo:
    import tarfile  # lazy: keeps worker start-up fast

    info = tarfile.TarInfo(name)
    info.size = size
    info.mode = 0o644
    info.mtime = int(mtime)
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    info.type = tarfile.REGTYPE
    return info


def _write_tar_zst(
    out: BinaryIO,
    reader: _HashingReader,
    entry_name: str,
    size: int,
    mtime: float,
    level: int,
    finish_manifest: Any,
) -> None:
    import tarfile  # lazy: keeps worker start-up fast

    zstd = _optional.zstandard()
    assert zstd is not None  # noqa: S101 - checked by the caller
    cctx = zstd.ZstdCompressor(level=level, threads=1)
    with cctx.stream_writer(out, closefd=False) as writer:
        with tarfile.open(
            fileobj=writer, mode="w|", format=tarfile.PAX_FORMAT, bufsize=CHUNK_SIZE
        ) as tf:
            tf.copybufsize = CHUNK_SIZE
            tf.addfile(_tar_info(entry_name, size, mtime), reader)  # type: ignore[arg-type]
            if reader.count != size:
                raise WorkerError(Code.INTEGRITY_FAILURE, "source changed while being archived")
            manifest = finish_manifest()
            tf.addfile(_tar_info(MANIFEST_NAME, len(manifest), time.time()), _BytesReader(manifest))  # type: ignore[arg-type]


def _large_zip_error() -> type[Exception]:
    import zipfile  # lazy: keeps worker start-up fast

    return zipfile.LargeZipFile


class _BytesReader:
    def __init__(self, data: bytes) -> None:
        self._data = data
        self._pos = 0

    def read(self, n: int = -1) -> bytes:
        end = len(self._data) if n is None or n < 0 else self._pos + n
        chunk = self._data[self._pos : end]
        self._pos += len(chunk)
        return chunk


def create_archive(
    source: str,
    dest: str,
    fmt: str,
    level: str,
    entry_name: str,
    expected_sha256: str | None = None,
    fail_if_larger: bool = True,
    host_manifest: dict[str, Any] | None = None,
    ctx: Context | None = None,
) -> dict[str, Any]:
    ctx = ctx or Context()
    if fmt not in FORMATS:
        raise WorkerError(Code.UNSUPPORTED_FORMAT, f"unsupported archive format: {fmt!r}")
    if fmt == "tar.zst" and not _optional.zstd_available():
        raise WorkerError(Code.UNSUPPORTED_FORMAT, "tar.zst requires the 'zstandard' package")
    if level not in LEVEL_NAMES:
        raise WorkerError(Code.BAD_REQUEST, "level must be one of fast|balanced|maximum")
    validate_entry_name(entry_name)
    expected = expected_sha256.lower() if expected_sha256 else None
    host = host_manifest or {}

    src_stat = os.stat(fs_path(source))
    if not stat.S_ISREG(src_stat.st_mode):
        raise WorkerError(Code.BAD_REQUEST, "source is not a regular file")
    size = src_stat.st_size
    if lexists(dest):
        raise WorkerError(Code.EXISTS, "destination already exists")

    ok = False
    created = False
    try:
        try:
            out = open(fs_path(dest), "xb")  # noqa: SIM115 - closed by the with block below
        except FileExistsError as exc:
            raise WorkerError(Code.EXISTS, "destination already exists") from exc
        created = True
        with out, open(fs_path(source), "rb") as handle:
            reader = _HashingReader(handle, size, ctx)

            def finish_manifest() -> bytes:
                if reader.count != size:
                    raise WorkerError(Code.INTEGRITY_FAILURE, "source changed while being archived")
                sha = reader.digest.hexdigest()
                if expected is not None and sha != expected:
                    raise WorkerError(Code.INTEGRITY_FAILURE, "source does not match expected_sha256")
                return _build_manifest(entry_name, size, sha, src_stat.st_mtime_ns, host)

            if fmt == "zip":
                _write_zip(
                    out, reader, entry_name, size, src_stat.st_mtime, ZIP_LEVELS[level], finish_manifest
                )
            else:
                _write_tar_zst(
                    out, reader, entry_name, size, src_stat.st_mtime, ZSTD_LEVELS[level], finish_manifest
                )
            out.flush()
            os.fsync(out.fileno())
            archive_size = out.tell()
        if fail_if_larger and archive_size >= size:
            raise WorkerError(Code.NO_BENEFIT, "archive would not be smaller than the source")
        ok = True
    except _large_zip_error() as exc:
        raise WorkerError(Code.LIMIT_EXCEEDED, str(exc)) from exc
    finally:
        if created and not ok:
            remove_quiet(dest)
    return {
        "archive": dest,
        "archive_size": archive_size,
        "source_size": size,
        "source_sha256": reader.digest.hexdigest(),
        "entry_name": entry_name,
        "format": fmt,
    }
