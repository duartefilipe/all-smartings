"""Protocolo Colmi/QRing (serviço 6e40fff0), usado pelo R02/R03/R06.

Pacote fixo de 16 bytes: byte0 = comando, bytes 1..14 = dados, byte15 = soma(0..14) & 0xFF.
Escrita em 6e400002 (sem resposta), respostas por notificação em 6e400003.
byte0 com o bit 0x80 ligado indica que o anel não aceitou o comando.

Fontes (engenharia reversa da comunidade, não oficial): tahnok/colmi_r02_client e colmi.puxtril.com.
"""

from __future__ import annotations

import asyncio
import time
from datetime import datetime
from typing import Callable, Optional

from ..ble import Session, to_hex


def build(cmd: int, payload: bytes = b"") -> bytes:
    pkt = bytearray(16)
    pkt[0] = cmd
    pkt[1:1 + min(len(payload), 14)] = payload[:14]
    pkt[15] = sum(pkt[:15]) & 0xFF
    return bytes(pkt)


def of_cmd(resps: list[bytes], cmd: int) -> list[bytes]:
    return [r for r in resps if len(r) == 16 and (r[0] & 0x7F) == cmd]


def is_error(pkt: bytes) -> bool:
    return bool(pkt[0] & 0x80)


def from_bcd(b: int) -> int:
    return (b >> 4) * 10 + (b & 0x0F)


def to_bcd(v: int) -> int:
    return ((v // 10) << 4) | (v % 10)


async def send(s: Session, cmd: int, payload: bytes, label: str, wait_s: float = 1.5,
               done: Optional[Callable[[list[bytes]], bool]] = None) -> list[bytes]:
    pkt = build(cmd, payload)
    s.logs.append(f">> [{label}]: [{to_hex(pkt)}]")
    await s.write(pkt, response=False)
    return await s.wait_responses(wait_s, done)


async def prepare(s: Session, result: dict) -> None:
    """Executado em toda sessão Colmi: firmware (Device Information) e bateria."""
    result["fw"] = s.name or "Colmi/QRing"
    fw = await s.read_text("00002a26")
    hw = await s.read_text("00002a27")
    if fw:
        result["fwRevision"] = fw
        parts = fw.split("_")  # 'RY03_3.00.18_240930' -> '3.00.18'
        result["fw"] = parts[1] if len(parts) >= 2 else fw
    if hw:
        result["hwRevision"] = hw
    if fw or hw:
        s.logs.append(f"[Device Info] Firmware: {fw} | Hardware: {hw}")
    for r in of_cmd(await send(s, 0x03, b"", "Colmi/QRing GetBattery (0x03)"), 0x03):
        if not is_error(r):
            result["battery"] = r[1]
            result["charging"] = r[2] == 1
            s.logs.append(f"[Colmi/QRing] Bateria lida: {r[1]}% (Carregando: {r[2]})")


async def steps_of_day(s: Session, day_offset: int = 0) -> list[dict]:
    """0x43: 43 <dia> 0F 00 5F 01. Respostas: F0 = cabeçalho, FF = sem dados; dados =
    ano/mês/dia BCD, slot de 15 min, índice, total, calorias LE, passos LE, distância LE (m)."""

    def done(resps: list[bytes]) -> bool:
        for p in of_cmd(resps, 0x43):
            if p[1] == 0xFF:
                return True
            if p[1] != 0xF0 and p[6] > 0 and p[5] >= p[6] - 1:
                return True
        return False

    resps = await send(s, 0x43, bytes([day_offset, 0x0F, 0x00, 0x5F, 0x01]),
                       f"Colmi Passos do dia (0x43, offset {day_offset})", 5.0, done)
    new_cal = False
    slots = []
    for p in of_cmd(resps, 0x43):
        if p[1] == 0xFF:
            s.logs.append("[Colmi] Sem registros de atividade para esse dia.")
            break
        if p[1] == 0xF0:
            new_cal = p[3] == 1
            continue
        idx = p[4]
        cal = p[7] | (p[8] << 8)
        slots.append({
            "date": f"20{from_bcd(p[1]):02d}-{from_bcd(p[2]):02d}-{from_bcd(p[3]):02d}",
            "time": f"{idx // 4:02d}:{(idx % 4) * 15:02d}",
            "steps": p[9] | (p[10] << 8),
            "calories": cal / 10.0 if new_cal else cal,
            "distance": p[11] | (p[12] << 8),
        })
    return slots


async def connect(s: Session, result: dict, body: dict) -> None:
    slots = await steps_of_day(s, 0)
    result["steps"] = sum(x["steps"] for x in slots)
    result["distance"] = sum(x["distance"] for x in slots)
    result["calories"] = int(sum(x["calories"] for x in slots))
    s.logs.append(f"[Colmi] Hoje: {result['steps']} passos, {result['distance']} m, "
                  f"{result['calories']} kcal ({len(slots)} blocos de 15 min)")


async def realtime_hr(s: Session, result: dict, seconds: int = 25) -> None:
    """0x69 01 01 inicia FC ao vivo; 0x69 01 03 mantém; 0x6A 01 00 00 para.
    Resposta: 69 <tipo> <erro> <bpm>. Precisa estar no dedo; no carregador vem erro."""
    await send(s, 0x69, b"\x01\x01", "Colmi Iniciar FC tempo real (0x69 01 01)")
    series: list[int] = []
    errors: set[int] = set()
    start = time.monotonic()
    last_continue = start
    seen = 0
    while time.monotonic() - start < seconds:
        await asyncio.sleep(0.5)
        if time.monotonic() - last_continue > 5:
            await s.write(build(0x69, b"\x01\x03"), response=False)
            last_continue = time.monotonic()
            seen = 0
        pkts = of_cmd(s.responses, 0x69)
        for p in pkts[seen:]:
            if p[2] != 0:
                errors.add(p[2])
            elif p[3] > 0:
                series.append(p[3])
        seen = len(pkts)
    await send(s, 0x6A, b"\x01\x00\x00", "Colmi Parar FC tempo real (0x6A 01 00 00)", 1.0)
    result["hrSeries"] = series
    if series:
        result["hr"] = series[-1]
        # Osciloscópio: série de FC normalizada (não é a onda PPG bruta; o protocolo não a expõe)
        result["rawSamples"] = [(v - 80.0) / 60.0 for v in series]
        s.logs.append(f"[Colmi] FC medida: {', '.join(map(str, series))} bpm")
    elif errors:
        s.logs.append(f"[Colmi] Anel respondeu com codigo de erro {', '.join(map(str, sorted(errors)))} "
                      "(provavelmente fora do dedo ou no carregador).")
    else:
        s.logs.append(f"[Colmi] Nenhuma leitura de FC em {seconds}s. Coloque o anel no dedo, fora do carregador.")


async def live_start(s: Session, result: dict, body: dict) -> None:
    await realtime_hr(s, result, int(body.get("seconds") or 25))


async def live_stop(s: Session, result: dict, body: dict) -> None:
    await send(s, 0x6A, b"\x01\x00\x00", "Colmi Parar FC tempo real (0x6A 01 00 00)", 1.0)


async def calibrate(s: Session, result: dict, body: dict) -> None:
    now = datetime.now()
    tp = bytes([to_bcd(now.year % 100), to_bcd(now.month), to_bcd(now.day),
                to_bcd(now.hour), to_bcd(now.minute), to_bcd(now.second), 0x01])
    await send(s, 0x01, tp, f"Colmi Sincronizar relogio {now:%d/%m/%Y %H:%M:%S} (0x01)")
    s.logs.append("[Colmi] Relogio sincronizado. Pressao/perfil biometrico nao existem no protocolo Colmi; nada mais foi gravado.")


async def gesture(s: Session, result: dict, body: dict) -> None:
    s.logs.append("[Colmi] Modo gesto nao esta documentado no protocolo Colmi/QRing; nenhum comando enviado.")


async def history(s: Session, result: dict, body: dict) -> None:
    records = []
    for d in range(3):
        for sl in await steps_of_day(s, d):
            records.append({"time": f"{sl['date']} {sl['time']}", "type": "Passos (15 min)",
                            "steps": sl["steps"], "distance": sl["distance"], "calories": sl["calories"]})
    result["records"] = records
    s.logs.append(f"[Colmi] Historico: {len(records)} blocos de atividade (hoje e 2 dias anteriores).")


# Configurações que mais gastam bateria. Leitura = byte1 01 (nunca escreve).
POWER_SETTINGS = [(0x2C, "SpO2 continuo"), (0x36, "Monitoramento de estresse (Pressure)"), (0x38, "HRV continuo")]


async def diagnostics(s: Session, result: dict, body: dict) -> None:
    checks = []
    t0 = time.monotonic()
    bat = of_cmd(await send(s, 0x03, b"", "Diag Colmi: Bateria (0x03)", 2.0), 0x03)
    lat = int((time.monotonic() - t0) * 1000)
    result["latencyMs"] = lat
    checks.append({"name": "Tempo de Resposta BLE", "status": "OK" if lat < 800 else "WARN",
                   "detail": f"{lat} ms para responder ao comando de bateria"})
    if bat:
        lvl, chg = bat[0][1], bat[0][2] == 1
        checks.append({"name": "Bateria", "status": "WARN" if lvl < 15 and not chg else "OK",
                       "detail": f"{lvl}% ({'carregando' if chg else 'descarregando'}). Valor estimado pelo firmware a partir da tensao."})
    else:
        checks.append({"name": "Bateria", "status": "ERR", "detail": "Sem resposta ao comando 0x03"})
    if result.get("fwRevision"):
        checks.append({"name": "Firmware / Hardware", "status": "OK",
                       "detail": f"{result['fwRevision']} / {result.get('hwRevision', '')}"})
    st = await steps_of_day(s, 0)
    checks.append({"name": "Memoria de atividade (0x43)", "status": "OK", "detail": f"{len(st)} blocos de 15 min lidos para hoje"})
    await send(s, 0x6A, b"\x01\x00\x00", "Diag Colmi: Parar FC orfa (0x6A)", 0.8)
    checks.append({"name": "Medicao de FC orfa", "status": "OK", "detail": "Comando de parada 0x6A enviado (LED liberado)"})

    hr = of_cmd(await send(s, 0x16, b"\x01", "Diag Colmi: Ler FC periodica (0x16 01)"), 0x16)
    if hr and not is_error(hr[0]):
        on, iv = hr[0][2] == 1, hr[0][3]
        detail = (f"LIGADA a cada {iv} min" + (" (intervalo curto gasta bateria; 30 min ou mais e mais economico)" if iv < 15 else "")) \
            if on else f"Desligada (intervalo salvo: {iv} min)"
        checks.append({"name": "Medicao automatica de FC", "status": "WARN" if on and iv < 15 else "OK", "detail": detail})
    for cmd, name in POWER_SETTINGS:
        p = of_cmd(await send(s, cmd, b"\x01", f"Diag Colmi: Ler {name} (0x{cmd:02X} 01)"), cmd)
        if p and not is_error(p[0]):
            on = p[0][2] == 1
            checks.append({"name": name, "status": "WARN" if on else "OK",
                           "detail": "LIGADO (mede o dia todo e gasta bateria)" if on else "Desligado"})
    checks.append({"name": "Sensores (PPG / acelerometro)", "status": "WARN",
                   "detail": "Protocolo Colmi nao tem registrador de falha documentado; use a medicao de FC ao vivo como teste do PPG"})
    result["checks"] = checks


async def raw(s: Session, result: dict, body: dict) -> None:
    # No console: "tipo" = comando, "id" = byte1, payload = bytes seguintes
    cmd = body["_cmd_type"]
    await send(s, cmd, bytes([body["_cmd_id"]]) + body["_payload"], f"Colmi Raw 0x{cmd:02X}", 2.0)


ACTIONS = {
    "connect": connect, "calibrate": calibrate, "live/start": live_start, "live/stop": live_stop,
    "gesture": gesture, "history": history, "diagnostics": diagnostics, "raw": raw,
}
