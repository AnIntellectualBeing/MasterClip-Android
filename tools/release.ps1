<#
.SYNOPSIS
  Builds the signed MasterClip Mobile APKs with the master key and puts them on the GitHub release.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File Z:\Projects\deliverables\MasterClipMobile\tools\release.ps1
  ... -NoUpload        (build + sign only; APKs land in dist\)

.DESCRIPTION
  Asks for the master.jks passwords (hidden; never stored or printed), runs the unit tests,
  gradlew assembleRelease with the signing passed through the environment, checks the
  signature, copies the APKs to dist\ and uploads them to the GitHub release v<versionName>.
#>
param(
  [string]$Keystore = 'Z:\Secrets\MasterKey\master.jks',
  [string]$Alias = 'master',
  [switch]$NoUpload
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

function Plain([securestring]$s) {
  $p = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p) }
}

if (-not (Test-Path -LiteralPath $Keystore)) { $Keystore = Read-Host "master.jks not found. Full path to the keystore" }
$store = Plain (Read-Host "Keystore password" -AsSecureString)
$key = Plain (Read-Host "Key password (Enter = same)" -AsSecureString)
if (-not $key) { $key = $store }

$version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
Write-Host "MasterClip Mobile $version" -ForegroundColor Cyan

try {
  [Environment]::SetEnvironmentVariable('ORG_GRADLE_PROJECT_android.injected.signing.store.file', $Keystore)
  [Environment]::SetEnvironmentVariable('ORG_GRADLE_PROJECT_android.injected.signing.store.password', $store)
  [Environment]::SetEnvironmentVariable('ORG_GRADLE_PROJECT_android.injected.signing.key.alias', $Alias)
  [Environment]::SetEnvironmentVariable('ORG_GRADLE_PROJECT_android.injected.signing.key.password', $key)
  & .\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
  if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
} finally {
  foreach ($n in 'store.file', 'store.password', 'key.alias', 'key.password') {
    [Environment]::SetEnvironmentVariable("ORG_GRADLE_PROJECT_android.injected.signing.$n", $null)
  }
  $store = $null; $key = $null
  & .\gradlew.bat --stop | Out-Null
}

$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$apksigner = Get-ChildItem "$sdk\build-tools\*\apksigner.bat" | Sort-Object FullName | Select-Object -Last 1
New-Item -ItemType Directory -Force dist | Out-Null
$out = @()
foreach ($abi in 'arm64-v8a', 'armeabi-v7a', 'x86_64') {
  $apk = "app\build\outputs\apk\release\app-$abi-release.apk"
  if (-not (Test-Path $apk)) { throw "missing $apk (was the release signed?)" }
  & $apksigner.FullName verify --print-certs $apk | Select-Object -First 2
  if ($LASTEXITCODE -ne 0) { throw "signature check failed for $abi" }
  $dst = "dist\MasterClip-$version-$abi.apk"
  Copy-Item $apk $dst -Force
  $out += $dst
}
Write-Host "Signed APKs:" -ForegroundColor Green
$out | ForEach-Object { Write-Host "  $_" }

if (-not $NoUpload) {
  $tag = "v$version"
  gh release view $tag 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { gh release create $tag --title "MasterClip Mobile $version" --notes-file RELEASE_NOTES.md }
  gh release upload $tag @out --clobber
  gh release edit $tag --draft=false
  Write-Host "Uploaded to the GitHub release $tag" -ForegroundColor Green
}
