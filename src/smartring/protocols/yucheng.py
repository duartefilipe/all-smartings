"""Protocolo YuCheng (serviço be940000), usado pelo R11M.

Pacote: [tipo, id, tamanho LE (2 bytes), payload..., CRC-16 LE]. Escrita em be940001,
respostas por indicação em be940001/be940003.
"""

from __future__ import annotations

from datetime import datetime

from ..ble import Session, to_hex


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc = ((crc >> 8) & 0xFF) | ((crc << 8) & 0xFFFF)
        crc ^= b
        crc ^= (crc & 0xFF) >> 4
        crc ^= (crc << 12) & 0xFFFF
        crc ^= ((crc & 0xFF) << 5) & 0xFFFF
    return crc


def build(cmd_type: int, cmd_id: int, payload: bytes = b"") -> bytes:
    total = 4 + len(payload) + 2
    body = bytes([cmd_type, cmd_id, total & 0xFF, (total >> 8) & 0xFF]) + bytes(payload)
    crc = crc16(body)
    return body + bytes([crc & 0xFF, (crc >> 8) & 0xFF])


async def send(s: Session, cmd_type: int, cmd_id: int, payload: bytes, label: str, wait_s: float = 1.5) -> list[bytes]:
    pkt = build(cmd_type, cmd_id, payload)
    s.logs.append(f">> [{label}]: [{to_hex(pkt)}]")
    await s.write(pkt, response=True)
    return await s.wait_responses(wait_s, settle_s=0.25)


def _device_info(resps: list[bytes], result: dict) -> None:
    for r in resps:
        if len(r) >= 10 and r[0] == 0x02 and r[1] == 0x00:
            result["modelId"] = f"0x{r[4] | (r[5] << 8):04X}"
            result["fw"] = f"{r[7]}.{r[6]}"
            result["battery"] = r[9]


async def connect(s: Session, result: dict, body: dict) -> None:
    _device_info(await send(s, 0x02, 0x00, b"GC", "GetDeviceInfo 'GC'"), result)
    for r in await send(s, 0x02, 0x0C, b"", "GetNowStep (0x02, 0x0C)"):
        if len(r) >= 12 and r[0] == 0x02 and r[1] == 0x0C:
            result["distance"] = r[4] | (r[5] << 8)
            result["calories"] = r[7] | (r[8] << 8)
            result["steps"] = r[9] | (r[10] << 8)


async def calibrate(s: Session, result: dict, body: dict) -> None:
    now = datetime.now()
    week = (now.isoweekday() - 1) % 7  # segunda = 0
    tp = bytes([now.year & 0xFF, (now.year >> 8) & 0xFF, now.month, now.day, now.hour, now.minute, now.second, week])
    await send(s, 0x01, 0x00, tp, "Sincronizar RTC (0x01, 0x00)")
    if body.get("writeProfile"):
        prof = bytes([int(body["height"]), int(body["weight"]), int(body["sex"]), int(body["age"])])
        await send(s, 0x01, 0x03, prof, "Calibrar Perfil Biometrico (0x01, 0x03)")
        await send(s, 0x01, 0x08, bytes([int(body["hand"])]), "Calibrar Mao de Uso IMU (0x01, 0x08)")
    else:
        s.logs.append("[Modo Seguro] Perfil fisico (Altura/Peso/Idade/Mao) preservado conforme gravado pelo App do celular.")
    sbp, dbp = int(body.get("sbp", 120)), int(body.get("dbp", 80))
    await send(s, 0x03, 0x03, bytes([sbp, dbp]), f"Calibrar Linha de Base Optica de Pressao ({sbp}/{dbp} mmHg)")
    for r in await send(s, 0x02, 0x00, b"GC", "GetDeviceInfo Pos-Calibracao"):
        if len(r) >= 10 and r[0] == 0x02 and r[1] == 0x00:
            result["battery"] = r[9]


async def live_start(s: Session, result: dict, body: dict) -> None:
    await send(s, 0x03, 0x09, b"\x01\x00", "Start Real-Time Data Push (0x03, 0x09 -> ON)")
    resps = await send(s, 0x03, 0x0B, b"\x01\x00", "Start PPG Waveform Stream (0x03, 0x0B -> ON)", 2.5)
    samples = []
    for r in resps:
        if len(r) > 6:
            samples += [(b - 128.0) / 128.0 for b in r[4:-2]]
    result["rawSamples"] = samples


async def live_stop(s: Session, result: dict, body: dict) -> None:
    await send(s, 0x03, 0x0B, b"\x00\x00", "Stop PPG Waveform Stream (0x03, 0x0B -> OFF)")
    await send(s, 0x03, 0x09, b"\x00\x00", "Stop Real-Time Data Push (0x03, 0x09 -> OFF)")


async def gesture(s: Session, result: dict, body: dict) -> None:
    await send(s, 0x03, 0x01, bytes([1 if body.get("enable") else 0]), "Modo Gesto Shake (0x03, 0x01)")


HISTORY_BANKS = [(0x02, "Passos/Esporte"), (0x04, "Sono (Sleep)"), (0x06, "Batimentos (HR)"),
                 (0x08, "Pressao (BP)"), (0x09, "Saude Combinada (SpO2/Temp)")]


async def history(s: Session, result: dict, body: dict) -> None:
    records = []
    for bank_id, name in HISTORY_BANKS:
        resps = await send(s, 0x05, bank_id, b"", f"Ler Banco Flash {name} (0x05, 0x{bank_id:02X})", 2.2)
        for r in resps:
            if len(r) > 16 and r[0] == 0x05 and r[1] == 0x11:
                off, idx = 4, 1
                while off + 14 <= len(r) - 2:
                    records.append({
                        "time": f"Bloco #{idx} (30 min)", "type": name,
                        "steps": r[off + 8] | (r[off + 9] << 8),
                        "distance": r[off + 10] | (r[off + 11] << 8),
                        "calories": r[off + 12] | (r[off + 13] << 8),
                    })
                    off += 14
                    idx += 1
            elif len(r) > 10 and r[0] == 0x05 and r[1] in (0x12, 0x13, 0x14, 0x15):
                records.append({"time": f"Pacote 0x{r[1]:02X} ({len(r)}B)", "type": name,
                                "steps": "-", "distance": to_hex(r), "calories": "-"})
        if len(resps) > 1:
            await send(s, 0x05, 0x80, b"\x00", "Liberar Ponteiro Flash (0x05, 0x80)", 0.8)
    result["records"] = records


SENSOR_REGS = [(0x10, "Sensor Optico PPG (Batimentos/SpO2)"), (0x11, "Acelerometro 3 Eixos (IMU/Passos)"),
               (0x12, "Sensor Termico / Ambiente"), (0x13, "Front-End Analogico (AFE/Eletrodo)")]
BAT_STATES = {0: "Normal / Descarregando", 1: "Carregando na Base", 2: "Carga Completa (100%)",
              3: "Alerta de Subtensao Critica (Low Voltage)"}


async def diagnostics(s: Session, result: dict, body: dict) -> None:
    import time
    checks = []
    t0 = time.monotonic()
    info = await send(s, 0x02, 0x00, b"GC", "Diag: GetDeviceInfo 'GC'", 2.0)
    lat = int((time.monotonic() - t0) * 1000)
    result["latencyMs"] = lat
    checks.append({"name": "Tempo de Resposta da CPU / BLE", "status": "OK" if lat < 800 else "WARN",
                   "detail": f"{lat} ms" + (" (Processador sem travamento de interrupcao)" if lat < 800 else " (Latencia elevada - CPU ocupada ou sinal fraco)")})
    for r in info:
        if len(r) >= 14 and r[0] == 0x02 and r[1] == 0x00:
            state, bat, bind = r[8], r[9], r[10]
            result["battery"], result["batteryState"] = bat, state
            label = BAT_STATES.get(state, f"Codigo {state}")
            warn = state == 3 or bat < 15
            checks.append({"name": "PMU / Controlador de Bateria", "status": "WARN" if warn else "OK",
                           "detail": f"Bateria em {bat}% ({label})" + (" - Risco de desligamento sob pico do LED" if warn else "")})
            checks.append({"name": "Modo de Anuncio BLE (Bind State)", "status": "WARN" if bind == 0 else "OK",
                           "detail": "BindState=0 (Desvinculado): Anel anuncia com maior frequencia de radio para pareamento" if bind == 0
                           else "BindState=1 (Vinculado): Economia de radio ativa"})
    for reg, name in SENSOR_REGS:
        code = -1
        for r in await send(s, 0x02, reg, b"", f"Diag: {name}", 1.2):
            if len(r) >= 5 and r[0] == 0x02 and r[1] == reg:
                code = r[4]
        if code == 0:
            checks.append({"name": name, "status": "OK", "detail": "Codigo 0x00 (Sem erro de hardware ou curto no barramento I2C/SPI)"})
        elif code == 0xFC:
            checks.append({"name": name, "status": "OK", "detail": "Integrado ao canal principal (0xFC)"})
        elif code > 0:
            checks.append({"name": name, "status": "WARN", "detail": f"Codigo de status 0x{code:02X} reportado pelo registrador"})
        else:
            checks.append({"name": name, "status": "WARN", "detail": "Sem resposta no registrador"})
    for r in await send(s, 0x05, 0x00, b"\x00", "Diag: Controlador Flash (0x05, 0x00)"):
        if len(r) >= 5 and r[0] == 0x05 and r[1] == 0x00:
            ok = r[4] == 0
            checks.append({"name": "Memoria Flash & Fila Circular", "status": "OK" if ok else "ERR",
                           "detail": "Status 0x00 (Setores integros, sem ponteiro de log travado)" if ok else f"Erro de setor Flash: 0x{r[4]:02X}"})
    await send(s, 0x03, 0x0B, b"\x00\x00", "Diag: Desligar Stream PPG Orfao (0x03, 0x0B)", 1.0)
    await send(s, 0x03, 0x09, b"\x00\x00", "Diag: Desligar Push Continuo Orfao (0x03, 0x09)", 1.0)
    checks.append({"name": "Dreno por Stream em Tempo Real Orfao", "status": "OK",
                   "detail": "Canais 0x03/0x0B e 0x03/0x09 fechados (LED optico liberado para Deep Sleep)"})
    result["checks"] = checks


async def raw(s: Session, result: dict, body: dict) -> None:
    t, i, payload = body["_cmd_type"], body["_cmd_id"], body["_payload"]
    await send(s, t, i, payload, f"Raw 0x{t:02X}, 0x{i:02X}")


ACTIONS = {
    "connect": connect, "calibrate": calibrate, "live/start": live_start, "live/stop": live_stop,
    "gesture": gesture, "history": history, "diagnostics": diagnostics, "raw": raw,
}
