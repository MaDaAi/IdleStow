"""Typed accessors for request parameters; every violation becomes BAD_REQUEST / UNSAFE_PATH."""

from __future__ import annotations

import re
from typing import Any

from ..errors import Code, WorkerError
from ..fsutil import validate_abs_path

_HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")


def _missing(name: str) -> WorkerError:
    return WorkerError(Code.BAD_REQUEST, f"missing required parameter '{name}'")


def path_param(params: dict[str, Any], name: str) -> str:
    if name not in params:
        raise _missing(name)
    return validate_abs_path(params[name], name)


def str_param(params: dict[str, Any], name: str) -> str:
    value = params.get(name)
    if value is None:
        raise _missing(name)
    if not isinstance(value, str) or not value:
        raise WorkerError(Code.BAD_REQUEST, f"'{name}' must be a non-empty string")
    return value


def int_param(params: dict[str, Any], name: str, default: int | None = None, minimum: int = 0) -> int:
    value = params.get(name)
    if value is None:
        if default is None:
            raise _missing(name)
        return default
    if type(value) is not int or value < minimum:
        raise WorkerError(Code.BAD_REQUEST, f"'{name}' must be an integer >= {minimum}")
    return value


def bool_param(params: dict[str, Any], name: str, default: bool) -> bool:
    value = params.get(name)
    if value is None:
        return default
    if not isinstance(value, bool):
        raise WorkerError(Code.BAD_REQUEST, f"'{name}' must be a boolean")
    return value


def level_param(params: dict[str, Any], default: str = "balanced") -> str:
    value = params.get("level", default)
    if value not in ("fast", "balanced", "maximum"):
        raise WorkerError(Code.BAD_REQUEST, "'level' must be one of fast|balanced|maximum")
    return value


def hex_param(params: dict[str, Any], name: str, optional: bool = False) -> str | None:
    value = params.get(name)
    if value is None:
        if optional:
            return None
        raise _missing(name)
    if not isinstance(value, str) or not _HEX64.match(value):
        raise WorkerError(Code.BAD_REQUEST, f"'{name}' must be a 64-character hex SHA-256")
    return value.lower()


def format_param(params: dict[str, Any]) -> str:
    return str_param(params, "format")


def dict_param(params: dict[str, Any], name: str) -> dict[str, Any]:
    value = params.get(name)
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise WorkerError(Code.BAD_REQUEST, f"'{name}' must be an object")
    return value
