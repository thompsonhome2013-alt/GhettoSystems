# Wireless ADB helper for Ghetto Systems / Pixel
# Usage:
#   .\wireless-debug.ps1 pair 192.168.50.x:xxxxx 123456
#   .\wireless-debug.ps1 connect 192.168.50.x:xxxxx
#   .\wireless-debug.ps1 status
#   .\wireless-debug.ps1 install
#   .\wireless-debug.ps1 usb-tcpip   # once, with USB plugged in

param(
    [Parameter(Position = 0)]
    [ValidateSet("pair", "connect", "status", "install", "usb-tcpip", "help")]
    [string]$Action = "help",

    [Parameter(Position = 1)]
    [string]$Address = "",

    [Parameter(Position = 2)]
    [string]$PairCode = ""
)

$ErrorActionPreference = "Stop"
$Adb = "C:\Users\Timmy\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
$StateFile = Join-Path $env:USERPROFILE ".gs2_wireless_adb.txt"

if (-not (Test-Path $Adb)) {
    Write-Error "adb not found at $Adb"
}

function Ensure-Server {
    & $Adb start-server | Out-Null
}

function Show-Status {
    Ensure-Server
    Write-Host "=== adb devices ===" -ForegroundColor Cyan
    & $Adb devices -l
    Write-Host "=== mdns services ===" -ForegroundColor Cyan
    & $Adb mdns services 2>$null
    if (Test-Path $StateFile) {
        Write-Host "=== last connect ===" -ForegroundColor Cyan
        Get-Content $StateFile
    }
}

function Do-Pair {
    if (-not $Address -or -not $PairCode) {
        Write-Error "Usage: .\wireless-debug.ps1 pair IP:PAIR_PORT PAIR_CODE"
    }
    Ensure-Server
    Write-Host "Pairing $Address ..." -ForegroundColor Yellow
    # adb pair accepts code on stdin if not passed as arg on some versions; use arg when available
    & $Adb pair $Address $PairCode
    if ($LASTEXITCODE -ne 0) { throw "pair failed" }
    Write-Host "Paired OK. Now connect with the *debugging* IP:PORT (not pairing port)." -ForegroundColor Green
}

function Do-Connect {
    if (-not $Address) {
        if (Test-Path $StateFile) {
            $Address = (Get-Content $StateFile -Raw).Trim()
        }
    }
    if (-not $Address) {
        Write-Error "Usage: .\wireless-debug.ps1 connect IP:PORT"
    }
    Ensure-Server
    Write-Host "Connecting $Address ..." -ForegroundColor Yellow
    & $Adb connect $Address
    if ($LASTEXITCODE -ne 0) { throw "connect failed" }
    Set-Content -Path $StateFile -Value $Address -Encoding utf8
    & $Adb devices -l
    Write-Host "Saved last address to $StateFile" -ForegroundColor Green
}

function Do-UsbTcpip {
    Ensure-Server
    $devs = & $Adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] } | Where-Object { $_ -and $_ -ne "List" }
    if (-not $devs) {
        Write-Error "Plug phone via USB, enable USB debugging, authorize this PC, then re-run."
    }
    Write-Host "Enabling TCP/IP mode on port 5555..." -ForegroundColor Yellow
    & $Adb tcpip 5555
    Start-Sleep -Seconds 2
    Write-Host "On the phone, note Wi‑Fi IP (Settings → About → Status, or Wireless debugging)." -ForegroundColor Cyan
    Write-Host "Then: .\wireless-debug.ps1 connect PHONE_IP:5555" -ForegroundColor Cyan
}

function Do-Install {
    Ensure-Server
    $line = & $Adb devices | Select-String "device$"
    if (-not $line) {
        Write-Error "No device. Run connect first."
    }
    if (-not (Test-Path $Apk)) {
        Write-Host "APK missing — building debug..." -ForegroundColor Yellow
        $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
        $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
        Push-Location (Join-Path $PSScriptRoot "..")
        try {
            .\gradlew.bat assembleDebug --no-daemon
        } finally {
            Pop-Location
        }
    }
    Write-Host "Installing $Apk ..." -ForegroundColor Yellow
    & $Adb install -r $Apk
}

switch ($Action) {
    "pair" { Do-Pair }
    "connect" { Do-Connect }
    "status" { Show-Status }
    "install" { Do-Install }
    "usb-tcpip" { Do-UsbTcpip }
    default {
        Write-Host @"
Wireless debugging helper
=========================

Phone (Pixel / Android 11+):
  1. Same Wi‑Fi as this PC (PC is on 192.168.50.211)
  2. Settings → Developer options → Wireless debugging → ON
  3. Tap "Pair device with pairing code"
  4. Note IP:port and 6-digit code
  5. On the Wireless debugging screen, also note the IP:port under "IP address & port"
     (that is the *connect* port — different from pairing port)

PC:
  .\wireless-debug.ps1 pair 192.168.50.xx:PAIR_PORT 123456
  .\wireless-debug.ps1 connect 192.168.50.xx:DEBUG_PORT
  .\wireless-debug.ps1 status
  .\wireless-debug.ps1 install

USB one-time (older method):
  Plug USB → authorize → .\wireless-debug.ps1 usb-tcpip
  Unplug → .\wireless-debug.ps1 connect PHONE_IP:5555

"@
    }
}
