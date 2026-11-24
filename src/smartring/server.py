"""Servidor HTTP local: serve o painel e a API /api/* usada por ele."""

from __future__ import annotations

import asyncio
import logging
import re
from importlib import resources

from aiohttp import web

from . import ble, store
from .protocols import colmi, yucheng

log = logging.getLogger("smartring")

# O rádio BLE atende uma operação por vez (scan ou sessão), como no script original
_ble_lock = asyncio.Lock()


def _index_html() -> bytes:
    return resources.files("smartring").joinpath("static/index.html").read_bytes()


async def index(_req: web.Request) -> web.Response:
    return web.Response(body=_index_html(), content_type="text/html", charset="utf-8")


async def api_rings(_req: web.Request) -> web.Response:
    rings = store.known_rings()
    return web.json_response({"ok": True, "rings": [{"mac": m, "name": n} for m, n in rings.items()]})


async def api_scan(req: web.Request) -> web.Response:
    body = await _json(req)
    seconds = float(body.get("seconds") or 5)
    async with _ble_lock:
        devices = await ble.scan(seconds)
    devices.sort(key=lambda d: d["rssi"], reverse=True)
    for d in devices:
        if not d["name"]:
            d["name"] = "(Dispositivo BLE)"
        if d["isRing"]:
            store.add_ring(d["mac"], None if d["name"] == "(Dispositivo BLE)" else d["name"])
    return web.json_response({"ok": True, "devices": devices,
                              "logs": [f"Scan BLE concluido: {len(devices)} dispositivos encontrados."]})


async def _json(req: web.Request) -> dict:
    if not req.can_read_body:
        return {}
    try:
        data = await req.json()
        return data if isinstance(data, dict) else {}
    except ValueError:
        return {}


def _parse_raw(body: dict) -> None:
    hexbyte = re.compile(r"^[0-9A-Fa-f]{1,2}$")
    tokens = [t for t in str(body.get("payload") or "").split() if t]
    for v in [body.get("cmdType", ""), body.get("cmdId", ""), *tokens]:
        if not hexbyte.match(str(v)):
            raise ValueError(f"Byte hexadecimal invalido: '{v}'")
    body["_cmd_type"] = int(body["cmdType"], 16)
    body["_cmd_id"] = int(body["cmdId"], 16)
    body["_payload"] = bytes(int(t, 16) for t in tokens)


async def run_action(action: str, body: dict) -> dict:
    mac = str(body.get("mac") or "").strip().upper()
    logs: list[str] = []
    result: dict = {"ok": False, "mac": mac, "logs": logs}
    if not re.fullmatch(r"[0-9A-F]{2}(:[0-9A-F]{2}){5}", mac):
        logs.append("Selecione um anel (MAC invalido ou vazio). Use Escanear BLE para encontrar os aneis.")
        return result
    if action == "raw":
        try:
            _parse_raw(body)
        except (ValueError, KeyError) as e:
            logs.append(str(e))
            return result

    adv = ble.last_advert(mac)
    if adv and adv.get("battery", 0) > 0:
        result.update(battery=adv["battery"], steps=adv["steps"], hr=adv["hr"])
        logs.append(f"[ADV] Ultimo anuncio do anel: Bateria={adv['battery']}%, Passos={adv['steps']}, RSSI={adv['rssi']} dBm")

    async with _ble_lock:
        try:
            async with ble.open_session(mac, logs) as s:
                if s.name:
                    result["name"] = s.name
                if s.protocol == "yucheng":
                    result.update(ok=True, protocol="yucheng")
                    await yucheng.ACTIONS[action](s, result, body)
                elif s.protocol == "colmi":
                    result.update(ok=True, protocol="colmi")
                    await colmi.prepare(s, result)
                    try:
                        await colmi.ACTIONS[action](s, result, body)
                    except Exception as e:  # mantém bateria/firmware mesmo se o comando falhar
                        logs.append(f"[Colmi] Erro no comando: {e}")
                else:
                    logs.append("Nenhum protocolo conhecido nos servicos GATT deste dispositivo.")
        except ble.RingError as e:
            logs.append(str(e))
        except asyncio.TimeoutError:
            logs.append(f"Tempo esgotado ao conectar em {mac}: o anel nao respondeu. Aproxime do PC e confirme que "
                         "ele nao esta conectado ao app do celular.")
        except Exception as e:
            log.exception("Falha na sessao com %s", mac)
            logs.append(f"Falha na conexao com {mac}: {e}. Se ele estiver conectado ao app do celular, desconecte primeiro.")

    if result["ok"]:
        store.add_ring(mac, result.get("name"))
    return result


def _action_handler(action: str):
    async def handler(req: web.Request) -> web.Response:
        return web.json_response(await run_action(action, await _json(req)))
    return handler


_LOCAL_HOSTS = {"localhost", "127.0.0.1", "[::1]"}


@web.middleware
async def local_only(req: web.Request, handler):
    # Bloqueia DNS rebinding (Host diferente de localhost) e CSRF de outros sites:
    # POST só com Content-Type JSON, que o navegador não envia entre origens sem CORS.
    host = (req.host or "").rsplit(":", 1)[0].lower()
    if host not in _LOCAL_HOSTS:
        return web.json_response({"ok": False, "logs": ["Host nao permitido"]}, status=403)
    if req.method == "POST" and req.content_type != "application/json":
        return web.json_response({"ok": False, "logs": ["Use Content-Type: application/json"]}, status=415)
    return await handler(req)


def create_app() -> web.Application:
    app = web.Application(middlewares=[local_only])
    app.router.add_get("/", index)
    app.router.add_get("/index.html", index)
    app.router.add_get("/api/rings", api_rings)
    app.router.add_post("/api/scan", api_scan)
    for action in yucheng.ACTIONS:
        app.router.add_post(f"/api/{action}", _action_handler(action))
    return app


def serve(host: str, port: int, open_browser: bool) -> None:
    url = f"http://{'localhost' if host in ('127.0.0.1', '0.0.0.0') else host}:{port}/"

    async def on_startup(_app: web.Application) -> None:
        log.info("Smart Ring Center rodando em %s (dados em %s)", url, store.data_dir())
        if open_browser:
            import webbrowser
            webbrowser.open(url)

    app = create_app()
    app.on_startup.append(on_startup)
    web.run_app(app, host=host, port=port, print=None, access_log=None)
