# Runs the Android test suites.
#   .\run-tests.ps1           -> JVM unit tests + instrumented integration tests on the connected device
#   .\run-tests.ps1 -UnitOnly -> JVM unit tests only
# The instrumented tests are installed and run with adb directly (not connectedAndroidTest),
# so the game and its saved progress stay on the device; only the test APK is removed.
# Full Gradle output is written to test.log.
param([switch]$UnitOnly)
$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }

$tasks = if ($UnitOnly) { 'testDebugUnitTest' } else { 'testDebugUnitTest assembleDebug assembleDebugAndroidTest' }
cmd /c ".\gradlew.bat $tasks --console=plain > test.log 2>&1"
$code = $LASTEXITCODE
Get-Content test.log -Tail 25
if ($code -ne 0) { Write-Host 'TESTS-FAILED (gradle)'; exit 1 }
Write-Host 'Unit test report: app\build\reports\tests\testDebugUnitTest\index.html'
if ($UnitOnly) { Write-Host 'TESTS-DONE'; exit 0 }

# UI tests inject touches, so the screen must be on (unlock the phone if it has a lock screen).
adb shell input keyevent KEYCODE_WAKEUP
adb install -r app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'adb install (app) failed' }
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'adb install (tests) failed' }
$out = adb shell am instrument -w com.utkarsh.pathpuzzle.test/android.test.InstrumentationTestRunner
$out | ForEach-Object { Write-Host $_ }
adb uninstall com.utkarsh.pathpuzzle.test | Out-Null
if (($out -join "`n") -match 'OK \(\d+ tests?\)') { Write-Host 'TESTS-DONE' } else { Write-Host 'TESTS-FAILED (device)'; exit 1 }
