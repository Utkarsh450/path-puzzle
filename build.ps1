# Builds the Path Puzzle debug APK and (optionally) installs it on a connected device.
#   .\build.ps1            -> assemble
#   .\build.ps1 -Install   -> assemble + adb install + launch
# Full Gradle output is written to build.log.
param([switch]$Install)
$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

# Prefer Android Studio's bundled JBR (JDK 21); fall back to whatever JAVA_HOME already is.
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }
Write-Host "JAVA_HOME = $env:JAVA_HOME"

# Create the Gradle wrapper once, from the locally cached Gradle 8.14.3 distribution.
if (-not (Test-Path '.\gradlew.bat')) {
    $gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\*\gradle-8.14.3\bin\gradle.bat" | Select-Object -First 1
    if (-not $gradle) { throw 'Gradle 8.14.3 is not cached; install it or open the project in Android Studio once.' }
    & $gradle.FullName wrapper --gradle-version 8.14.3 --distribution-type bin
    if ($LASTEXITCODE -ne 0) { throw 'gradle wrapper failed' }
}

cmd /c ".\gradlew.bat assembleDebug --console=plain > build.log 2>&1"
$code = $LASTEXITCODE
Get-Content build.log -Tail 25
if ($code -ne 0) { Write-Host 'BUILD-SCRIPT-FAILED'; exit 1 }
$apk = 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host "APK: $apk"

if ($Install) {
    adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
    adb shell am start -n com.utkarsh.pathpuzzle/com.arrowpuzzle.game.MainActivity
}
Write-Host 'BUILD-SCRIPT-DONE'
