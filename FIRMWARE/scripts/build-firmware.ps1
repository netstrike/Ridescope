param(
    [string[]]$PlatformioArgs = @("run")
)

$ErrorActionPreference = "Stop"

$firmwareRoot = Split-Path -Parent $PSScriptRoot
$candidateCommands = @()

$platformioPenv = Join-Path $env:USERPROFILE ".platformio\penv\Scripts\platformio.exe"
if (Test-Path -LiteralPath $platformioPenv) {
    $candidateCommands += $platformioPenv
}

$pioCommand = Get-Command "pio.exe" -ErrorAction SilentlyContinue
if ($pioCommand) {
    $candidateCommands += $pioCommand.Source
}

$platformioCommand = Get-Command "platformio.exe" -ErrorAction SilentlyContinue
if ($platformioCommand) {
    $candidateCommands += $platformioCommand.Source
}

if ($candidateCommands.Count -eq 0) {
    throw "PlatformIO non trovato. Installa PlatformIO o usa il virtualenv in $env:USERPROFILE\.platformio\penv."
}

$platformio = $candidateCommands[0]
Write-Host "Uso PlatformIO: $platformio"

Push-Location $firmwareRoot
try {
    & $platformio @PlatformioArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
