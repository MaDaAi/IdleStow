"""Work operations: parameter validation plus delegation to the domain modules."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from ..analysis.estimate import DEFAULT_CHUNK, DEFAULT_MAX_SAMPLE, estimate_compressibility
from ..compression.archive import create_archive
from ..context import Context
from ..monitoring import system_status
from ..verification.archive import extract_archive, inspect_archive, verify_archive
from ..verification.hashing import hash_file
from . import params as p

Handler = Callable[[dict[str, Any], Context], Any]


def op_hash_file(params: dict[str, Any], ctx: Context) -> dict[str, Any]:
    path = p.path_param(params, "path")
    sha, size = hash_file(path, ctx)
    return {"sha256": sha, "size": size}


def op_estimate(params: dict[str, Any], ctx: Context) -> dict[str, Any]:
    path = p.path_param(params, "path")
    return estimate_compressibility(
        path,
        max_sample_bytes=p.int_param(params, "max_sample_bytes", DEFAULT_MAX_SAMPLE),
        chunk_size=p.int_param(params, "chunk_size", DEFAULT_CHUNK, minimum=1),
        level=p.level_param(params),
        ctx=ctx,
    )


def op_create_archive(params: dict[str, Any], ctx: Context) -> dict[str, Any]:
    source = p.path_param(params, "source")
    dest = p.path_param(params, "dest")
    return create_archive(
        source=source,
        dest=dest,
        fmt=p.format_param(params),
        level=p.level_param(params),
        entry_name=p.str_param(params, "entry_name"),
        expected_sha256=p.hex_param(params, "expected_sha256", optional=True),
        fail_if_larger=p.bool_param(params, "fail_if_larger", True),
        host_manifest=p.dict_param(params, "manifest"),
        ctx=ctx,
    )


def op_verify_archive(params: dict[str, Any], ctx: Context) -> dict[str, Any]:
    return verify_archive(
        archive=p.path_param(params, "archive"),
        fmt=p.format_param(params),
        entry_name=p.str_param(params, "entry_name"),
        expected_sha256=p.hex_param(params, "expected_sha256") or "",
        expected_size=p.int_param(params, "expected_size"),
        ctx=ctx,
    )


def op_extract_archive(params: dict[str, Any], ctx: Context) -> dict[str, Any]:
    return extract_archive(
        archive=p.path_param(params, "archive"),
        fmt=p.format_param(params),
        entry_name=p.str_param(params, "entry_name"),
        dest=p.path_param(params, "dest"),
        expected_sha256=p.hex_param(params, "expected_sha256") or "",
        expected_size=p.int_param(params, "expected_size"),
        ctx=ctx,
    )


def op_inspect_archive(params: dict[str, Any], ctx: Context) -> dict[str, Any]:
    return inspect_archive(p.path_param(params, "archive"), p.format_param(params), ctx)


def op_system_status(params: dict[str, Any], ctx: Context) -> dict[str, Any]:
    return system_status()


WORK_OPS: dict[str, Handler] = {
    "hash_file": op_hash_file,
    "estimate_compressibility": op_estimate,
    "create_archive": op_create_archive,
    "verify_archive": op_verify_archive,
    "extract_archive": op_extract_archive,
    "inspect_archive": op_inspect_archive,
    "system_status": op_system_status,
}
