# Builds the signed release bundle for Google Play and copies it to dist\.
#   .\build-release.ps1            -> LIVE build: your real AdMob ids (signing\ads.properties). For production.
#   .\build-release.ps1 -TestAds   -> TEST build: Google's sample ads only. Use this for anything you hand to
#                                     testers (internal or closed testing), so nobody can click a real ad.
# Raise versionCode in app\build.gradle.kts before every upload; Play refuses a number it has seen.
# Needs signing\keystore.properties and signing\pathpuzzle-upload.jks (back both up; see store\RELEASE.md).
param([switch]$TestAds)
Set-Location -Path $PSScriptRoot

if (-not (Test-Path 'signing\keystore.properties')) { Write-Host 'MISSING signing\keystore.properties'; exit 1 }
if (-not $TestAds -and -not (Test-Path 'signing\ads.properties')) {
    Write-Host 'MISSING signing\ads.properties: a live build needs your real AdMob ids (see signing\ads.properties.example).'
    exit 1
}
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }

$flag = ''
if ($TestAds) { $flag = '-PtestAds=true' }
cmd /c ".\gradlew.bat bundleRelease $flag --console=plain > release.log 2>&1"
$code = $LASTEXITCODE
Get-Content release.log -Tail 15
if ($code -ne 0) { Write-Host 'RELEASE-BUILD-FAILED'; exit 1 }
$aab = 'app\build\outputs\bundle\release\app-release.aab'
if (-not (Test-Path $aab)) { Write-Host 'RELEASE-BUILD-FAILED (no aab)'; exit 1 }

$gradle = Get-Content 'app\build.gradle.kts' -Raw
$vc = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$kind = 'LIVE-real-ads'
if ($TestAds) { $kind = 'TEST-ads-only' }
New-Item -ItemType Directory -Force -Path 'dist' | Out-Null
$out = "dist\path-puzzle-v$vc-$kind.aab"
Copy-Item $aab $out -Force
Write-Host ("AAB: $out (" + [math]::Round((Get-Item $out).Length / 1MB, 1) + ' MB, versionCode ' + $vc + ', ' + $kind + ')')
& "$jbr\bin\keytool.exe" -printcert -jarfile $aab | Select-String 'Owner|SHA256'
Write-Host 'RELEASE-BUILD-DONE'
