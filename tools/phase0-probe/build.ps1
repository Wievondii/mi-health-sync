$ErrorActionPreference = 'Stop'
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk -or -not $env:JAVA_HOME) { throw '需要配置 Android SDK 和 JDK 17。' }
$tools = Join-Path $sdk 'build-tools/35.0.0'
$androidJar = Join-Path $sdk 'platforms/android-35/android.jar'
$bin = Join-Path $env:JAVA_HOME 'bin'
$out = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Force -Path $out, $classes | Out-Null

function Invoke-BuildTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "构建命令失败：$Executable（退出码 $LASTEXITCODE）" }
}

$manifest = Join-Path $PSScriptRoot 'AndroidManifest.xml'
$source = Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' -File | Select-Object -ExpandProperty FullName
$source = @($source) + (Join-Path $PSScriptRoot '../../cloud/src/main/java/com/mihealthsync/cloud/MiCloud.java')
$unsigned = Join-Path $out 'unsigned.apk'
$aligned = Join-Path $out 'aligned.apk'
$apk = Join-Path $out 'mihealth-cloud-probe.apk'
$classJar = Join-Path $out 'classes.jar'
Invoke-BuildTool (Join-Path $tools 'aapt2.exe') @('link', '-I', $androidJar, '--manifest', $manifest, '-o', $unsigned)
Invoke-BuildTool (Join-Path $bin 'javac.exe') (@('-encoding', 'UTF-8', '--release', '8', '-classpath', $androidJar, '-d', $classes) + $source)
Invoke-BuildTool (Join-Path $bin 'jar.exe') @('cf', $classJar, '-C', $classes, '.')
Invoke-BuildTool (Join-Path $tools 'd8.bat') @('--lib', $androidJar, '--min-api', '28', '--output', $out, $classJar)
$licenseRoot = Join-Path $PSScriptRoot '../../cloud/src/main/resources'
Invoke-BuildTool (Join-Path $bin 'jar.exe') @('uf', $unsigned, '-C', $out, 'classes.dex', '-C', $licenseRoot, 'META-INF/mihealth-sync-licenses.txt')
Invoke-BuildTool (Join-Path $tools 'zipalign.exe') @('-f', '4', $unsigned, $aligned)

# 使用项目外的 Android 调试签名。只有本机尚无调试签名时才生成。
$debugStore = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android/debug.keystore'
if (-not (Test-Path -LiteralPath $debugStore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $debugStore) | Out-Null
    Invoke-BuildTool (Join-Path $bin 'keytool.exe') @('-genkeypair', '-keystore', $debugStore, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US', '-keyalg', 'RSA', '-validity', '10000')
}
Invoke-BuildTool (Join-Path $tools 'apksigner.bat') @('sign', '--ks', $debugStore, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $apk, $aligned)
Invoke-BuildTool (Join-Path $tools 'apksigner.bat') @('verify', $apk)
Write-Output $apk
