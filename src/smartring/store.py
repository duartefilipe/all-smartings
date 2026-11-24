"""Dados locais do usuário (fora do repositório): anéis conhecidos, logs e PID do servidor."""

from __future__ import annotations

import json
import os
from pathlib import Path


def data_dir() -> Path:
    base = os.environ.get("SMARTRING_DATA")
    if base:
        p = Path(base)
    elif os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        p = Path(os.environ["LOCALAPPDATA"]) / "SmartRingCenter"
    else:
        p = Path.home() / ".smartring"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _rings_file() -> Path:
    return data_dir() / "aneis_conhecidos.json"


def known_rings() -> dict[str, str]:
    try:
        return {k.upper(): str(v) for k, v in json.loads(_rings_file().read_text("utf-8")).items()}
    except (OSError, ValueError):
        return {}


def add_ring(mac: str, name: str | None) -> None:
    if not mac:
        return
    mac = mac.upper()
    rings = known_rings()
    if mac in rings and (not name or rings[mac] == name):
        return
    rings[mac] = name or rings.get(mac) or "Smart Ring"
    _rings_file().write_text(json.dumps(rings, ensure_ascii=False, indent=2), "utf-8")
