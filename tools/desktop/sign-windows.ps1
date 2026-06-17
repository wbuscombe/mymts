# Sign MyMTS.exe — GATED on a code-signing certificate.
#
# With WINDOWS_CERT_PFX (+ WINDOWS_CERT_PASSWORD) present, signtool signs the exe
# with a timestamp. Without it, the exe is left unsigned and the SmartScreen
# caveat is printed — the build still succeeds. Windows code-signing certificates
# are harder/costlier to obtain than Apple's; unsigned is acceptable for now.
#
# No secrets are printed.
param(
    [string]$Exe = "$PSScriptRoot\dist\MyMTS\MyMTS.exe"
)
$ErrorActionPreference = "Stop"

if (-not (Test-Path $Exe)) { Write-Error "no exe at $Exe (build it first)"; exit 1 }

if (-not $env:WINDOWS_CERT_PFX -or -not (Test-Path $env:WINDOWS_CERT_PFX)) {
    Write-Host "==> UNSIGNED build (no WINDOWS_CERT_PFX)."
    Write-Host "    A downloaded unsigned exe triggers the SmartScreen 'unrecognized app'"
    Write-Host "    warning: users click 'More info' -> 'Run anyway' (once)."
    Write-Host "    To sign: set WINDOWS_CERT_PFX (path to a .pfx) + WINDOWS_CERT_PASSWORD."
    exit 0
}

$signtool = (Get-Command signtool.exe -ErrorAction SilentlyContinue)
if (-not $signtool) { Write-Error "signtool.exe not found (install the Windows SDK)"; exit 1 }

Write-Host "==> signtool sign (+ RFC3161 timestamp)"
& signtool sign /f $env:WINDOWS_CERT_PFX /p $env:WINDOWS_CERT_PASSWORD `
    /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 $Exe
& signtool verify /pa $Exe
Write-Host "==> signed."
