"""Camada BLE: varredura, cache de dispositivos e sessão GATT com detecção de protocolo."""

from __future__ import annotations

import asyncio
import time
from contextlib import asynccontextmanager
from typing import Awaitable, Callable, Optional

from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic
from bleak.backends.device import BLEDevice

RING_UUID_PREFIXES = ("be940000", "6e40fff0", "de5bf728", "0000fee7", "0000ae00")
RING_COMPANIES = {0x7810, 0xBCBC}
# Apple, Samsung, Garmin, Huami/Amazfit, etc.: nunca são anéis
EXCLUDED_COMPANIES = {0x004C, 0x0075, 0x0065, 0x0157, 0x0087}
RING_NAME_PREFIXES = ("R11", "R0", "R1", "COLMI", "SR08", "M-REX")

YC_SERVICE = "be940000"
COLMI_SERVICES = ("6e40fff0", "6e400001")


class RingError(Exception):
    pass


def to_hex(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


# Último BLEDevice e resumo de anúncio vistos por MAC. No Windows o BLEDevice continua
# válido para conectar mesmo depois, porque o bleak abre o dispositivo pelo endereço.
_devices: dict[str, BLEDevice] = {}
_adverts: dict[str, dict] = {}


def last_advert(mac: str) -> Optional[dict]:
    return _adverts.get(mac.upper())


def _is_ring_name(name: str) -> bool:
    up = (name or "").upper()
    return up.startswith(RING_NAME_PREFIXES) or "RING" in up


async def scan(seconds: float) -> list[dict]:
    found: dict[str, dict] = {}

    def on_adv(dev: BLEDevice, adv) -> None:
        mac = dev.address.upper()
        s = found.setdefault(mac, {
            "mac": mac, "name": "", "rssi": adv.rssi, "mfg": "", "uuids": [], "info": "",
            "battery": 0, "steps": 0, "hr": 0, "isRing": False,
        })
        s["rssi"] = adv.rssi
        if adv.local_name:
            s["name"] = adv.local_name
        for u in adv.service_uuids:
            u = u.lower()
            if u not in s["uuids"]:
                s["uuids"].append(u)
            if u.startswith(RING_UUID_PREFIXES):
                s["isRing"] = True
        excluded = False
        for cid, data in adv.manufacturer_data.items():
            if cid in EXCLUDED_COMPANIES:
                excluded = True
            s["mfg"] = f"0x{cid:04X}: {to_hex(data)}"
            if cid in RING_COMPANIES:
                s["isRing"] = True
            if cid == 0x7810 and len(data) >= 20:
                # Anúncio do R11M/YuCheng carrega passos, distância, FC e bateria
                s["steps"] = data[0] | (data[1] << 8)
                dist = data[12] | (data[13] << 8)
                s["hr"] = data[16] | (data[17] << 8)
                s["battery"] = data[18]
                s["info"] = f"Bat: {s['battery']}% | Passos: {s['steps']} | Dist: {dist}m | FC: {s['hr']} bpm"
        if _is_ring_name(s["name"]):
            s["isRing"] = True
        if excluded:
            s["isRing"] = False
        _devices[mac] = dev
        _adverts[mac] = dict(s, seen=time.time())

    async with BleakScanner(detection_callback=on_adv, scanning_mode="active"):
        await asyncio.sleep(seconds)
    result = []
    for s in found.values():
        result.append(dict(s, uuids=",".join(s["uuids"])))
    return result


async def find_device(mac: str, timeout: float = 20.0) -> Optional[BLEDevice]:
    mac = mac.upper()
    if mac in _devices:
        return _devices[mac]
    dev = await BleakScanner.find_device_by_address(mac, timeout=timeout)
    if dev is not None:
        _devices[mac] = dev
    return dev


class Session:
    """Conexão GATT aberta com um anel. Notificações vão para `responses` e para o log."""

    def __init__(self, client: BleakClient, logs: list[str]):
        self.client = client
        self.logs = logs
        self.protocol: Optional[str] = None
        self.name: str = ""
        self.responses: list[bytes] = []
        self._event = asyncio.Event()
        self.write_char: Optional[BleakGATTCharacteristic] = None

    def service_uuids(self) -> list[str]:
        return [s.uuid.lower() for s in self.client.services]

    def find_char(self, prefix: str) -> Optional[BleakGATTCharacteristic]:
        for svc in self.client.services:
            for ch in svc.characteristics:
                if ch.uuid.lower().startswith(prefix):
                    return ch
        return None

    def _listener(self, label: str) -> Callable[[BleakGATTCharacteristic, bytearray], None]:
        def cb(_ch: BleakGATTCharacteristic, data: bytearray) -> None:
            b = bytes(data)
            self.responses.append(b)
            self.logs.append(f"<< [{label}] ({len(b)}B): [{to_hex(b)}]")
            self._event.set()
        return cb

    async def subscribe(self, prefix: str, label: str) -> None:
        ch = self.find_char(prefix)
        if ch is None:
            raise RingError(f"Característica {prefix} não encontrada")
        await self.client.start_notify(ch, self._listener(label))

    async def read_text(self, prefix: str) -> str:
        ch = self.find_char(prefix)
        if ch is None:
            return ""
        try:
            data = await self.client.read_gatt_char(ch)
        except Exception:
            return ""
        return bytes(data).decode("ascii", "replace").rstrip("\x00")

    async def write(self, pkt: bytes, response: bool) -> None:
        self.responses = []
        self._event.clear()
        await self.client.write_gatt_char(self.write_char, pkt, response=response)

    async def wait_responses(self, wait_s: float, done: Optional[Callable[[list[bytes]], bool]] = None,
                             settle_s: float = 0.15) -> list[bytes]:
        """Espera respostas até `wait_s`. Sem `done`, devolve logo após o primeiro pacote."""
        deadline = time.monotonic() + wait_s
        while time.monotonic() < deadline:
            try:
                await asyncio.wait_for(self._event.wait(), timeout=0.2)
            except asyncio.TimeoutError:
                pass
            self._event.clear()
            if done is None:
                if self.responses:
                    await asyncio.sleep(settle_s)
                    break
            elif done(list(self.responses)):
                break
        return list(self.responses)


@asynccontextmanager
async def open_session(mac: str, logs: list[str]):
    dev = await find_device(mac)
    if dev is None:
        raise RingError(f"Anel {mac} não encontrado. Ele pode estar fora de alcance ou conectado ao celular.")
    client = BleakClient(dev, timeout=20, winrt={"use_cached_services": False})
    await client.connect()
    try:
        s = Session(client, logs)
        s.name = dev.name or ""
        uuids = s.service_uuids()
        if any(u.startswith(YC_SERVICE) for u in uuids):
            s.protocol = "yucheng"
            s.write_char = s.find_char("be940001")
            await s.subscribe("be940001", "be940001")
            await s.subscribe("be940003", "be940003")
        elif any(u.startswith(COLMI_SERVICES) for u in uuids):
            s.protocol = "colmi"
            s.write_char = s.find_char("6e400002")
            await s.subscribe("6e400003", "Colmi-TX")
        logs.append(f"[Auto-Detect] Nome='{s.name}' | Protocolo={s.protocol or 'desconhecido'} | Serviços GATT: {', '.join(uuids)}")
        yield s
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass


SessionAction = Callable[[Session, dict, dict], Awaitable[None]]
