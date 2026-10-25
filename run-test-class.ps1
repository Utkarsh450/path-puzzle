# Builds and runs ONE device test class (or one method) on the connected phone, for quick checks:
#   .\run-test-class.ps1 ChaosTest
#   .\run-test-class.ps1 ChaosTest#testDamagedSavedGamesAreSurvived
param([Parameter(Mandatory = $true)][string]$Test)
$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

$state = (& adb get-state 2>$null)
if ($state -ne 'device') { Write-Host "NO PHONE ($state): connect it, unlock it and allow USB debugging."; exit 1 }
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }
cmd /c ".\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain > class-build.log 2>&1"
if ($LASTEXITCODE -ne 0) { Get-Content class-build.log -Tail 30; Write-Host 'BUILD-FAILED'; exit 1 }
adb install -r app\build\outputs\apk\debug\app-debug.apk | Out-Null
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk | Out-Null
adb shell input keyevent KEYCODE_WAKEUP
$cls = "com.arrowpuzzle.game.$Test"
$out = adb shell am instrument -w -e class $cls com.utkarsh.pathpuzzle.test/android.test.InstrumentationTestRunner
$out | ForEach-Object { Write-Host $_ }
if (($out -join "`n") -match 'OK \(\d+ tests?\)') { Write-Host 'CLASS-TESTS-PASSED' } else { Write-Host 'CLASS-TESTS-FAILED'; exit 1 }
