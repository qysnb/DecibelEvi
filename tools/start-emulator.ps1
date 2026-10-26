param([switch]$Visible, [string]$SdkPath = $env:ANDROID_HOME, [string]$Gpu = 'host')
$ErrorActionPreference = 'Stop'
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) { throw 'Set ANDROID_HOME or pass -SdkPath pointing to your Android SDK.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:ANDROID_HOME = $SdkPath
$env:ANDROID_SDK_ROOT = $SdkPath
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-home'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools\avd'
$env:TEMP = Join-Path $projectRoot '.tools\tmp'
$env:TMP = $env:TEMP
$logFolder = Join-Path $projectRoot '.tools\runtime-check'
New-Item -ItemType Directory -Force -Path $logFolder,$env:TEMP | Out-Null
$connected = & (Join-Path $SdkPath 'platform-tools\adb.exe') devices
if ($connected -match '^emulator-5554\s') {
    Write-Output 'emulator-5554 is already running. Reuse it; no second emulator was started.'
    exit 0
}
$emulatorArguments = @('-avd','DecibelEvi_API35','-port','5554','-no-boot-anim','-no-snapshot-save','-no-audio','-gpu',$Gpu,'-feature','-Vulkan')
if (-not $Visible) { $emulatorArguments += '-no-window' }
$windowMode = if ($Visible) { 'Normal' } else { 'Hidden' }
$process = Start-Process -FilePath (Join-Path $SdkPath 'emulator\emulator.exe') -ArgumentList $emulatorArguments -WorkingDirectory $projectRoot -WindowStyle $windowMode -RedirectStandardOutput (Join-Path $logFolder 'emulator-stdout.log') -RedirectStandardError (Join-Path $logFolder 'emulator-stderr.log') -PassThru
Write-Output "Emulator launcher PID: $($process.Id); AVD/cache/temp directories are under $projectRoot\.tools"
