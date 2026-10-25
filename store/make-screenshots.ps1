# Regenerates the store art on the connected phone and copies it into store\ safely:
#   .\store\make-screenshots.ps1
# Runs StoreShotsTest (the real game drawn off-screen on test-only prefs, so the player's save is never
# touched), pulls every PNG into a temp folder first, and only replaces a file in store\ when the new
# copy is a real image (never overwrites good art with an empty file if the phone drops out).
$ErrorActionPreference = 'Stop'
Set-Location -Path (Split-Path -Parent $PSScriptRoot)

$state = (& adb get-state 2>$null)
if ($state -ne 'device') { Write-Host "NO PHONE ($state): connect it, unlock it and allow USB debugging. Nothing was changed."; exit 1 }

$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }
cmd /c ".\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain > shots-build.log 2>&1"
if ($LASTEXITCODE -ne 0) { Get-Content shots-build.log -Tail 30; Write-Host 'SHOTS-BUILD-FAILED'; exit 1 }

adb install -r app\build\outputs\apk\debug\app-debug.apk | Out-Null
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk | Out-Null
adb shell input keyevent KEYCODE_WAKEUP
$out = adb shell am instrument -w -e class com.arrowpuzzle.game.StoreShotsTest com.utkarsh.pathpuzzle.test/android.test.InstrumentationTestRunner
$out | ForEach-Object { Write-Host $_ }
if (($out -join "`n") -notmatch 'OK \(1 test\)') { Write-Host 'SHOTS-TEST-FAILED: store\ was not changed.'; exit 1 }

$tmp = Join-Path $env:TEMP ('pp-shots-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
New-Item -ItemType Directory -Force -Path 'store\screenshots' | Out-Null
$names = @('feature-graphic-1024x500.png') + (1..13 | ForEach-Object { 'phone-{0:00}.png' -f $_ })
foreach ($n in $names) {
    $dest = Join-Path $tmp $n
    cmd /c "adb exec-out run-as com.utkarsh.pathpuzzle cat files/store-shots/$n > `"$dest`""
    if ((Test-Path $dest) -and ((Get-Item $dest).Length -gt 1000)) {
        if ($n -like 'feature*') { Copy-Item $dest "store\$n" -Force } else { Copy-Item $dest "store\screenshots\$n" -Force }
    } else {
        Write-Host "SKIPPED $n (not a real image); the old file was kept"
    }
}
Remove-Item -Recurse -Force $tmp
Get-ChildItem store\feature-graphic-1024x500.png, store\screenshots | Select-Object Name, Length
Write-Host 'SHOTS-DONE'
