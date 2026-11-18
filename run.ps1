param(
    [switch]$SkipInstall,
    [switch]$SkipSoftReboot
)

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$adb = 'D:\adb.exe'
$apk = Join-Path $root 'build\WeComLoc.apk'
$lsposedDir = '/data/adb/modules/zygisk_lsposed'
$dbLocal = Join-Path $root 'build\lsposed_modules.db'

function Fail($msg) {
    Write-Host "ERROR: $msg" -ForegroundColor Red
    exit 1
}

& $adb devices | Out-Host
$dev = (& $adb devices) | Select-String -Pattern 'ANEB024B08001982\s+device'
if (-not $dev) {
    Fail '手机没连上 ADB。请重新插 USB、打开“USB 调试”，解锁屏幕后重试。'
}

if (-not $SkipInstall) {
    Write-Host '[1/4] 安装模块 APK（需要屏幕已解锁并允许安装）'
    & $adb install -r -g $apk | Out-Host
}

$pathLine = (& $adb shell pm path com.fj.wecomloc 2>$null) -join ''
if ($pathLine -notmatch 'package:') {
    Fail '模块 APK 没有安装成功；先在手机上点“允许安装”，再重跑本脚本。'
}
$apkPath = $pathLine -replace '^package:', '' -replace "`r", ''
Write-Host "installed: $apkPath"

Write-Host '[2/4] 检查临时 root'
$id = (& $adb shell "su -c id" 2>&1) -join ''
if ($id -notmatch 'uid=0') {
    Fail 'su 不可用。先恢复临时 root，再重跑；不要硬重启手机。'
}

Write-Host '[3/4] 写入 LSPosed 模块状态和 scope（先停 daemon，避免 WAL 覆盖）'
& $adb shell "su -c 'sh $lsposedDir/lspctl stop'" 2>&1 | Out-Host
& $adb shell "su -c 'cp /data/adb/lspd/config/modules_config.db /sdcard/Download/lsposed_modules.db; chmod 644 /sdcard/Download/lsposed_modules.db'" 2>&1 | Out-Host
& $adb pull /sdcard/Download/lsposed_modules.db $dbLocal 2>&1 | Out-Host

& python (Join-Path $root 'tools\lsposed_enable.py') $dbLocal $apkPath 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { Fail '数据库写入失败' }

& $adb push $dbLocal /data/local/tmp/lsposed_modules.db 2>&1 | Out-Host
& $adb shell "su -c 'rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm; cp /data/local/tmp/lsposed_modules.db /data/adb/lspd/config/modules_config.db; chown root:root /data/adb/lspd/config/modules_config.db; chmod 600 /data/adb/lspd/config/modules_config.db; restorecon /data/adb/lspd/config/modules_config.db'" 2>&1 | Out-Host

if (-not $SkipSoftReboot) {
    Write-Host '[4/4] 软重启 zygote，让 LSPosed 重新加载模块（不要硬重启）'
    & $adb shell "su -c 'setprop ctl.restart zygote'" 2>&1 | Out-Host
    Start-Sleep -Seconds 8
    & $adb wait-for-device
    for ($i = 0; $i -lt 60; $i++) {
        $b = (& $adb shell getprop sys.boot_completed 2>$null) -join ''
        if ($b.Trim() -eq '1') { break }
        Start-Sleep -Seconds 2
    }
}

Write-Host '完成。打开企业微信 -> 打卡/外出打卡；然后用 WeComLoc 设坐标并点“写入并应用”。'
Write-Host '日志: adb logcat -s WeComLoc'
