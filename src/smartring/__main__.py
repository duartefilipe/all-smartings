"""Linha de comando: smartring [serve|start|stop|status|startup|monitor]."""

from __future__ import annotations

import argparse
import json
import logging
import os
import subprocess
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path

from . import __version__, store

DEFAULT_PORT = 8765
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_NAME = "SmartRingCenter"


def _pid_file() -> Path:
    return store.data_dir() / "server.pid"


def _log_file() -> Path:
    return store.data_dir() / "server.log"


def _pythonw() -> str:
    exe = Path(sys.executable)
    w = exe.with_name("pythonw.exe")
    return str(w if w.exists() else exe)


def _api(port: int, path: str, body: dict | None = None, timeout: float = 120) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data,
                                 headers={"Content-Type": "application/json"} if data else {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def _running(port: int) -> bool:
    try:
        return bool(_api(port, "/api/rings", timeout=3).get("ok"))
    except OSError:
        return False


def cmd_serve(a: argparse.Namespace) -> None:
    handlers: list[logging.Handler] = [logging.FileHandler(_log_file(), encoding="utf-8")]
    if sys.stdout is not None:  # pythonw não tem console
        handlers.append(logging.StreamHandler(sys.stdout))
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", handlers=handlers)
    if _running(a.port):
        logging.error("Ja existe um servidor na porta %s. Use 'smartring stop' antes.", a.port)
        sys.exit(1)
    _pid_file().write_text(str(os.getpid()))
    try:
        from .server import serve
        serve(a.host, a.port, open_browser=not a.no_browser)
    finally:
        try:
            if _pid_file().read_text().strip() == str(os.getpid()):
                _pid_file().unlink()
        except OSError:
            pass


def cmd_start(a: argparse.Namespace) -> None:
    if _running(a.port):
        print(f"Servidor ja esta rodando em http://localhost:{a.port}/")
    else:
        flags = 0
        if os.name == "nt":
            flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
        subprocess.Popen([_pythonw(), "-m", "smartring", "serve", "--no-browser", "--port", str(a.port)],
                         creationflags=flags, close_fds=True, stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=(os.name != "nt"))
        for _ in range(40):
            time.sleep(0.5)
            if _running(a.port):
                break
        if not _running(a.port):
            print(f"O servidor nao respondeu. Veja o log: {_log_file()}")
            sys.exit(1)
        print(f"Servidor rodando em segundo plano: http://localhost:{a.port}/")
    if not a.no_browser:
        import webbrowser
        webbrowser.open(f"http://localhost:{a.port}/")


def cmd_stop(a: argparse.Namespace) -> None:
    try:
        pid = int(_pid_file().read_text().strip())
    except (OSError, ValueError):
        print("Nenhum servidor em segundo plano registrado.")
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)
    else:
        import signal
        os.kill(pid, signal.SIGTERM)
    try:
        _pid_file().unlink()
    except OSError:
        pass
    print(f"Servidor (PID {pid}) encerrado.")


def cmd_status(a: argparse.Namespace) -> None:
    if _running(a.port):
        print(f"Rodando em http://localhost:{a.port}/ | dados em {store.data_dir()}")
    else:
        print(f"Parado (porta {a.port}). Log: {_log_file()}")


def cmd_startup(a: argparse.Namespace) -> None:
    if os.name != "nt":
        print("Inicializacao automatica so esta implementada para Windows.")
        sys.exit(1)
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_ALL_ACCESS) as k:
        if a.action == "on":
            cmd = f'"{_pythonw()}" -m smartring serve --no-browser --port {a.port}'
            winreg.SetValueEx(k, RUN_NAME, 0, winreg.REG_SZ, cmd)
            print(f"Ativado: o servidor inicia com o Windows ({cmd})")
        elif a.action == "off":
            try:
                winreg.DeleteValue(k, RUN_NAME)
                print("Desativado: o servidor nao inicia mais com o Windows.")
            except FileNotFoundError:
                print("Ja estava desativado.")
        else:
            try:
                print("Ativado: " + winreg.QueryValueEx(k, RUN_NAME)[0])
            except FileNotFoundError:
                print("Desativado.")


def cmd_monitor(a: argparse.Namespace) -> None:
    """Lê a bateria pela API em intervalos e grava CSV com a taxa em %/hora."""
    out = Path(a.out) if a.out else store.data_dir() / f"bateria_{a.mac.replace(':', '')}.csv"
    new = not out.exists()
    first = None
    with out.open("a", encoding="utf-8") as f:
        if new:
            f.write("horario,bateria,carregando,pct_por_hora\n")
        for i in range(a.count):
            now = datetime.now()
            try:
                r = _api(a.port, "/api/connect", {"mac": a.mac})
                bat = int(r.get("battery") or 0)
                chg = "" if r.get("charging") is None else int(bool(r.get("charging")))
                rate = ""
                if bat > 0:
                    if first is None:
                        first = (now, bat)
                    hours = (now - first[0]).total_seconds() / 3600
                    if hours > 0.2:
                        rate = round((bat - first[1]) / hours, 2)
                line = f"{now:%d/%m %H:%M:%S},{bat or 'ERRO'},{chg},{rate}"
            except OSError as e:
                line = f"{now:%d/%m %H:%M:%S},ERRO,,{e}"
            f.write(line + "\n")
            f.flush()
            print(line)
            if i < a.count - 1:
                time.sleep(a.interval)
    print(f"CSV: {out}")


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(prog="smartring", description="Smart Ring Center: painel local para aneis BLE.")
    p.add_argument("--version", action="version", version=__version__)
    sub = p.add_subparsers(dest="cmd")

    def port_arg(sp: argparse.ArgumentParser) -> None:
        sp.add_argument("--port", type=int, default=DEFAULT_PORT)

    sp = sub.add_parser("serve", help="roda o servidor neste terminal (padrao)")
    port_arg(sp)
    sp.add_argument("--host", default="127.0.0.1", help="interface (padrao: so este PC)")
    sp.add_argument("--no-browser", action="store_true")
    sp.set_defaults(fn=cmd_serve)

    sp = sub.add_parser("start", help="roda o servidor em segundo plano")
    port_arg(sp)
    sp.add_argument("--no-browser", action="store_true")
    sp.set_defaults(fn=cmd_start)

    for name, fn, h in (("stop", cmd_stop, "encerra o servidor em segundo plano"),
                        ("status", cmd_status, "mostra se o servidor esta rodando")):
        sp = sub.add_parser(name, help=h)
        port_arg(sp)
        sp.set_defaults(fn=fn)

    sp = sub.add_parser("startup", help="liga/desliga o inicio automatico com o Windows")
    sp.add_argument("action", choices=["on", "off", "status"])
    port_arg(sp)
    sp.set_defaults(fn=cmd_startup)

    sp = sub.add_parser("monitor", help="grava a bateria de um anel em CSV (servidor precisa estar rodando)")
    sp.add_argument("--mac", required=True)
    sp.add_argument("--interval", type=int, default=1800, help="segundos entre leituras (padrao 1800)")
    sp.add_argument("--count", type=int, default=48)
    sp.add_argument("--out", help="arquivo CSV (padrao: pasta de dados)")
    port_arg(sp)
    sp.set_defaults(fn=cmd_monitor)

    a = p.parse_args(argv)
    if a.cmd is None:
        a = p.parse_args(["serve", *(argv or sys.argv[1:])])
    a.fn(a)


if __name__ == "__main__":
    main()
