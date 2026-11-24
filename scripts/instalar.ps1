# Cria o ambiente Python do projeto e instala o comando `smartring`.
# Uso: powershell -ExecutionPolicy Bypass -File scripts\instalar.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Get-Command python -ErrorAction SilentlyContinue)) { throw 'Python 3.10+ nao encontrado no PATH.' }
if (-not (Test-Path .venv)) { python -m venv .venv }
.\.venv\Scripts\python.exe -m pip install --quiet --upgrade pip
.\.venv\Scripts\python.exe -m pip install --quiet -e .
Write-Output ''
Write-Output 'Instalado. Comandos:'
Write-Output '  .\.venv\Scripts\smartring.exe start         # roda em segundo plano e abre o painel'
Write-Output '  .\.venv\Scripts\smartring.exe stop          # encerra'
Write-Output '  .\.venv\Scripts\smartring.exe startup on    # inicia junto com o Windows'
