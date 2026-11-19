$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$sdk = 'D:\Document\deepseek-harness\default-workspace\android-sdk'
$javaHome = 'D:\IntelliJ IDEA 2025.1.7\jbr'
$bt = Join-Path $sdk 'build-tools\35.0.0'
$platform = Join-Path $sdk 'platforms\android-35\android.jar'
$api = Join-Path $root 'libs\api-82.jar'
$build = Join-Path $root 'build'

$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

New-Item -ItemType Directory -Force -Path (Join-Path $build 'classes'), (Join-Path $build 'dex') | Out-Null

Write-Host '[1/5] javac'
$src = Get-ChildItem (Join-Path $root 'java') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
& (Join-Path $javaHome 'bin\javac.exe') --release 8 -encoding UTF-8 `
    -cp "$platform;$api" -d (Join-Path $build 'classes') $src 2>&1 | Out-Host
if (-not (Test-Path (Join-Path $build 'classes\com\fj\wecomloc\Module.class'))) {
    throw 'javac failed'
}

Write-Host '[2/5] d8'
# d8.bat explodes once the class list exceeds the cmd line limit, so pack the
# classes into a zip first and feed d8 a single input.
$dexOut = Join-Path $build 'dex'
if (Test-Path (Join-Path $dexOut 'classes.dex')) { Remove-Item (Join-Path $dexOut 'classes.dex') -Force }
$classZip = Join-Path $build 'classes.zip'
if (Test-Path $classZip) { Remove-Item $classZip -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $build 'classes'), $classZip)
& (Join-Path $bt 'd8.bat') --min-api 24 --lib $platform --output $dexOut $classZip 2>&1 | Out-Host
if (-not (Test-Path (Join-Path $dexOut 'classes.dex'))) { throw 'd8 failed' }

Write-Host '[3/5] aapt2 compile/link'
$resZip = Join-Path $build 'res.zip'
if (Test-Path (Join-Path $root 'res')) {
    & (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $root 'res') -o $resZip 2>&1 | Out-Host
}
$linkArgs = @('link', '-o', (Join-Path $build 'base.apk'),
    '-I', $platform,
    '--manifest', (Join-Path $root 'AndroidManifest.xml'),
    '--min-sdk-version', '24', '--target-sdk-version', '35',
    '--auto-add-overlay', '--version-code', '12', '--version-name', '1.11')
if (Test-Path $resZip) { $linkArgs += @('-R', $resZip) }
& (Join-Path $bt 'aapt2.exe') @linkArgs 2>&1 | Out-Host

Write-Host '[4/5] inject + align'
& python (Join-Path $root 'tools\inject.py') 2>&1 | Out-Host
& (Join-Path $bt 'zipalign.exe') -f 4 (Join-Path $build 'unaligned.apk') (Join-Path $build 'aligned.apk') 2>&1 | Out-Host

Write-Host '[5/5] sign'
$ks = Join-Path $build 'wecomloc.jks'
if (-not (Test-Path $ks)) {
    & (Join-Path $javaHome 'bin\keytool.exe') -genkeypair -v -keystore $ks -alias wecomloc `
        -keyalg RSA -keysize 2048 -validity 10000 -storepass wecomloc123 -keypass wecomloc123 `
        -dname 'CN=WeComLoc, OU=FJ, O=FJ, L=Beijing, ST=Beijing, C=CN' 2>&1 | Out-Host
}
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-key-alias wecomloc `
    --ks-pass pass:wecomloc123 --key-pass pass:wecomloc123 `
    --out (Join-Path $build 'WeComLoc.apk') (Join-Path $build 'aligned.apk') 2>&1 | Out-Host
& (Join-Path $bt 'apksigner.bat') verify --print-certs (Join-Path $build 'WeComLoc.apk') 2>&1 | Out-Host

Get-Item (Join-Path $build 'WeComLoc.apk') | Select-Object FullName, Length
