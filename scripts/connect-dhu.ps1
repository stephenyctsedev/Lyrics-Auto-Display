<#
.SYNOPSIS
    接 Desktop Head Unit，順便處理埋幾個會靜靜整死你嘅位。

.DESCRIPTION
    次序係有意思嘅，而且每一步都驗證過先行下一步 —— 「駁到」同「駁啱」
    唔係同一回事，中間幾個坑都係會靜靜失敗，唔會報錯：

      * USB 模式一改，adb 就重連，**個 port forward 會靜靜冇咗**。
        所以一定要確認咗模式先開 forward。
      * DHU 掟落 background 行，佢印完 "connected." 就即刻退出。
        一定要自己一個 window。
      * Samsung 預設唔畀第三方 app 出 logcat。冇 setprop 嘅話，
        個 app 明明行緊都會一句 log 都見唔到，睇落似死咗咁。
      * CI 每次 build 用一個新嘅 debug key，所以 `adb install -r`
        會 fail，一定要先 uninstall —— 而 uninstall 會連通知權限
        一齊清走，嗰個權限 adb 寫唔返，一定要落手機撳。

.PARAMETER Install
    先裝呢個 APK。會自動 uninstall 舊嗰個（簽名多數唔夾），
    所以裝完要重新開通知權限。

.PARAMETER Logcat
    開一個 window 睇住 app 個 log。

.PARAMETER Release
    針對 release build（com.stephen.autolyrics）而唔係 debug 版。

.EXAMPLE
    .\scripts\connect-dhu.ps1
    淨係接 DHU，唔郁部機裝住嘅嘢。

.EXAMPLE
    .\scripts\connect-dhu.ps1 -Install .\AutoLyrics-v0.2.3.apk -Logcat
    裝完、開 log、再接 DHU。
#>

[CmdletBinding()]
param(
    [string]$Install,
    [switch]$Logcat,
    [switch]$Release
)

$ErrorActionPreference = 'Stop'

$Sdk     = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$Adb     = Join-Path $Sdk 'platform-tools\adb.exe'
$Dhu     = Join-Path $Sdk 'extras\google\auto\desktop-head-unit.exe'
$Package = if ($Release) { 'com.stephen.autolyrics' } else { 'com.stephen.autolyrics.debug' }
$LogTag  = 'AutoLyricsBrowser'
$Port    = 5277

function Step($n, $text) { Write-Host "`n[$n] $text" -ForegroundColor Cyan }
function Ok($text)       { Write-Host "    ok   $text" -ForegroundColor Green }
function Warn($text)     { Write-Host "    !!   $text" -ForegroundColor Yellow }
function Die($text)      { Write-Host "`n     x   $text`n" -ForegroundColor Red; exit 1 }

foreach ($tool in @($Adb, $Dhu)) {
    if (-not (Test-Path $tool)) {
        Die "搵唔到 $tool`n         SDK Manager 裝返 platform-tools / Android Auto Desktop Head Unit Emulator"
    }
}

# --- 1. 部機 -----------------------------------------------------------
Step 1 '搵部機'
$devices = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
if (-not $devices)            { Die '冇機。插 USB，同埋喺手機撳「允許 USB 偵錯」。' }
if ($devices.Count -gt 1)     { Die "接咗 $($devices.Count) 部機，一次得一部。" }
Ok ($devices[0] -split '\s+')[0]

# --- 2. USB 模式（一定要喺 forward 之前）--------------------------------
Step 2 '睇 USB 模式'
$usb = (& $Adb shell getprop sys.usb.config).Trim()
if ($usb -notmatch 'mtp') {
    Warn "而家係 '$usb'，唔係檔案傳輸模式"
    Write-Host '         落手機通知欄改做「傳輸檔案 / MTP」，然後再行呢個 script。' -ForegroundColor Yellow
    Write-Host '         （改完 adb 會重連，forward 會冇咗，所以要由頭嚟過。）' -ForegroundColor DarkGray
    Die 'USB 模式唔啱。'
}
Ok $usb

# --- 3. 裝 APK（會清走通知權限）-----------------------------------------
if ($Install) {
    Step 3 "裝 $(Split-Path $Install -Leaf)"
    if (-not (Test-Path $Install)) { Die "搵唔到 $Install" }

    $installed = & $Adb shell pm list packages $Package 2>$null
    if ($installed) {
        # CI 每次 build 都係新 runner，即係新 debug keystore，
        # 所以 -r 一定 fail。唔試住，直接 uninstall。
        Warn "移除舊嘅 $Package（CI 每次 build 簽名都唔同，唔 uninstall 裝唔到）"
        & $Adb uninstall $Package | Out-Null
    }

    $out = & $Adb install -r $Install 2>&1
    if ($out -match 'Success') { Ok '裝好' } else { Die "裝唔到：`n$out" }

    Write-Host ''
    Warn '通知權限跟住 uninstall 冇咗，adb 寫唔返（Samsung 唔認）。'
    Write-Host '         而家開緊嗰版設定 —— 搵「Auto Lyrics」開返佢。' -ForegroundColor Yellow
    & $Adb shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS | Out-Null
    Read-Host "`n         開好之後撳 Enter 繼續"

    $granted = & $Adb shell dumpsys notification |
        Select-String -Pattern 'All notification listeners' -Context 0, 10 |
        Select-String -Pattern ([regex]::Escape($Package))
    if ($granted) { Ok '通知權限開咗' }
    else          { Warn '仲係見唔到權限 —— 冇咗佢讀唔到播放狀態，就冇歌詞。' }
}

# --- 4. Logcat（Samsung 預設收埋）---------------------------------------
Step 4 '開 app 個 log'
& $Adb shell setprop "log.tag.$LogTag" I
# App 要重新開過先讀到條 prop。
& $Adb shell am force-stop $Package
Ok "log.tag.$LogTag = I（Samsung 預設會收埋第三方 app 個 log）"

if ($Logcat) {
    & $Adb logcat -c
    Start-Process powershell -ArgumentList @(
        '-NoExit', '-Command',
        "& '$Adb' logcat -s ${LogTag}:I"
    )
    Ok '開咗個 log window'
}

# --- 5. Port forward（USB 模式確認咗先做）-------------------------------
Step 5 "開 port forward tcp:$Port"
& $Adb forward --remove-all 2>$null | Out-Null
& $Adb forward "tcp:$Port" "tcp:$Port" | Out-Null
$fwd = & $Adb forward --list | Select-String "tcp:$Port"
if (-not $fwd) { Die 'forward 開唔到。' }
Ok $fwd.ToString().Trim()

# --- 6. 手機開 head unit server ----------------------------------------
Step 6 '等手機開 head unit server'
# /proc/net/tcp 用十六進制，5277 = 149D。0A = LISTEN，01 = ESTABLISHED。
function Get-PortState {
    $lines = & $Adb shell "cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null"
    $states = $lines | ForEach-Object {
        $f = ($_ -split '\s+') | Where-Object { $_ }
        if ($f.Count -ge 4 -and ($f[1] -match '149D$' -or $f[2] -match '149D$')) { $f[3] }
    }
    [pscustomobject]@{
        Listening   = [bool]($states -contains '0A')
        Established = [int](@($states | Where-Object { $_ -eq '01' }).Count)
    }
}

if (-not (Get-PortState).Listening) {
    Write-Host '         手機:  Android Auto → 三點選單 → Start head unit server' -ForegroundColor Yellow
    Write-Host '         等緊…' -ForegroundColor DarkGray -NoNewline
    $waited = 0
    while (-not (Get-PortState).Listening) {
        Start-Sleep -Seconds 2
        $waited += 2
        if ($waited % 10 -eq 0) { Write-Host '.' -NoNewline -ForegroundColor DarkGray }
        if ($waited -ge 120)    { Write-Host ''; Die '等咗兩分鐘都未開到 server。' }
    }
    Write-Host ''
}
Ok "手機喺 $Port 聽住"

# --- 7. DHU（一定要自己一個 window）-------------------------------------
Step 7 '開 DHU'
Get-Process desktop-head-unit -ErrorAction SilentlyContinue | ForEach-Object {
    Warn "殺咗舊嘅 DHU (PID $($_.Id))"
    Stop-Process -Id $_.Id -Force
}
# 掟落 background 嘅話，佢印完 "connected." 就即刻退出 —— 要自己一個 console。
Start-Process -FilePath $Dhu -WorkingDirectory (Split-Path $Dhu)

$connected = $false
foreach ($i in 1..15) {
    Start-Sleep -Seconds 2
    if ((Get-PortState).Established -ge 1) { $connected = $true; break }
}

if ($connected) {
    Ok "接通咗（$((Get-PortState).Established) 條 ESTABLISHED）"
    Write-Host "`n     全部搞掂。播首歌，然後喺 DHU 撳音樂 icon → Auto Lyrics。`n" -ForegroundColor Green
} else {
    Warn 'DHU 開咗但仲未接通。'
    Write-Host '         多數係手機嗰邊個 server 停咗 —— 撳多次 Start head unit server。' -ForegroundColor Yellow
}
