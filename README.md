# all-smartings

Painel local para anéis inteligentes Bluetooth LE. Ele roda no seu PC, conecta direto no anel (sem nuvem) e abre no navegador em `http://localhost:8765/`.

| Anel | Protocolo | O que o painel faz |
|---|---|---|
| R11M e outros do protocolo YuCheng (serviço `be940000`) | pacotes com CRC-16 | bateria, passos, onda PPG ao vivo, histórico Flash, calibração, autoteste, console |
| Colmi R02/R03/R06 (app QRing, serviço `6e40fff0`) | pacotes de 16 bytes com soma | bateria e carga, firmware/hardware, passos do dia, histórico de 15 min, FC ao vivo, relógio, autoteste com configurações de energia, console |

O protocolo de cada anel é detectado sozinho ao conectar.

> Os formatos dos comandos vêm de engenharia reversa da comunidade (não são documentação oficial dos fabricantes). Use por sua conta e risco; os comandos de escrita (relógio, calibração, console) alteram o anel.

## Requisitos

- Windows 10/11 com Bluetooth LE (também deve funcionar em Linux e macOS pelo `bleak`, sem teste)
- Python 3.10 ou mais novo

## Instalação

```powershell
git clone https://github.com/duartefilipe/all-smartings.git
cd all-smartings
powershell -ExecutionPolicy Bypass -File scripts\instalar.ps1
```

## Uso

```powershell
.\.venv\Scripts\smartring.exe start          # servidor em segundo plano + abre o painel
.\.venv\Scripts\smartring.exe status
.\.venv\Scripts\smartring.exe stop
.\.venv\Scripts\smartring.exe serve          # roda no terminal atual (Ctrl+C para parar)
.\.venv\Scripts\smartring.exe startup on     # inicia junto com o Windows (off desativa)
```

`scripts\SmartRing.bat` faz o mesmo que `start` com dois cliques.

### Teste de bateria

Com o servidor rodando, grava a bateria em CSV e calcula a variação em %/hora:

```powershell
.\.venv\Scripts\smartring.exe monitor --mac AA:BB:CC:DD:EE:FF --interval 1800 --count 48
```

Cada leitura abre uma conexão BLE curta; intervalos longos interferem menos no consumo do anel.

## Onde ficam os dados

Nada pessoal fica no repositório. Anéis conhecidos, logs e CSVs ficam em `%LOCALAPPDATA%\SmartRingCenter` (ou `~/.smartring` fora do Windows). A variável `SMARTRING_DATA` muda esse local. O perfil de calibração fica só no navegador.

## Segurança

O servidor escuta só em `127.0.0.1`, recusa requisições com `Host` diferente de localhost e só aceita `POST` com `Content-Type: application/json`. Isso impede que outro site aberto no navegador mande comandos para o anel.

## Estrutura

```
src/smartring/
  __main__.py        linha de comando (serve, start, stop, status, startup, monitor)
  server.py          servidor HTTP (aiohttp) e API /api/*
  ble.py             scan, cache de dispositivos e sessão GATT (bleak)
  store.py           pasta de dados do usuário
  protocols/
    yucheng.py       R11M e similares
    colmi.py         Colmi/QRing
  static/index.html  painel
```

## Referências de protocolo

- [tahnok/colmi_r02_client](https://github.com/tahnok/colmi_r02_client)
- [colmi.puxtril.com](https://colmi.puxtril.com/)
- [atc1441/ATC_RF03_Ring](https://github.com/atc1441/ATC_RF03_Ring)
