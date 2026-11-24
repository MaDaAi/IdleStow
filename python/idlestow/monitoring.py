"""system_status: CPU load and battery via psutil when available."""

from __future__ import annotations

from typing import Any

from . import _optional


def system_status() -> dict[str, Any]:
    psutil = _optional.psutil()
    if psutil is None:
        return {"cpu_percent": None, "battery": None, "source": "none"}
    cpu: float | None
    try:
        cpu = float(psutil.cpu_percent(interval=0.1))
    except Exception:  # noqa: BLE001 - monitoring is best-effort
        cpu = None
    battery: dict[str, Any] | None = None
    try:
        info = psutil.sensors_battery()
        if info is not None:
            battery = {"percent": float(info.percent), "plugged": bool(info.power_plugged)}
    except Exception:  # noqa: BLE001
        battery = None
    return {"cpu_percent": cpu, "battery": battery, "source": "psutil"}
