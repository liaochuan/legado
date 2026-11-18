[CmdletBinding()]
param(
    [string]$Config,
    [string]$OutputDir,
    [switch]$SkipVerify
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
if (-not $Config) {
    $Config = Join-Path $ScriptDir 'liaocl-build.local.json'
}

function Fail([string]$Message) {
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    exit 1
}

function Require-Path([string]$Path, [string]$Name) {
    if (-not (Test-Path -LiteralPath $Path)) {
        Fail "$Name not found: $Path"
    }
}

if (-not (Test-Path -LiteralPath $Config)) {
    Fail "Config not found: $Config. Copy packaging/liaocl-build.example.json to packaging/liaocl-build.local.json and fill in the values."
}

$cfg = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json

foreach ($key in @('androidHome', 'javaHome', 'storeFile', 'keyAlias', 'storePassword', 'keyPassword')) {
    if (-not $cfg.PSObject.Properties[$key] -or [string]::IsNullOrWhiteSpace([string]$cfg.$key)) {
        Fail "Missing '$key' in $Config"
    }
}

$appId = 'top.liaocl.legado'
if ($cfg.PSObject.Properties['appId'] -and -not [string]::IsNullOrWhiteSpace([string]$cfg.appId)) {
    $appId = ([string]$cfg.appId).Trim()
}
$abi = 'arm64-v8a'
if ($cfg.PSObject.Properties['abi'] -and -not [string]::IsNullOrWhiteSpace([string]$cfg.abi)) {
    $abi = ([string]$cfg.abi).Trim()
}

$androidHome = [Environment]::ExpandEnvironmentVariables([string]$cfg.androidHome)
$javaHome = [Environment]::ExpandEnvironmentVariables([string]$cfg.javaHome)
$storeFile = [Environment]::ExpandEnvironmentVariables([string]$cfg.storeFile)

Require-Path $androidHome 'Android SDK'
Require-Path $javaHome 'JDK'
Require-Path $storeFile 'Keystore'

$javaExe = Join-Path $javaHome 'bin\java.exe'
Require-Path $javaExe 'java.exe'

$repoRoot = Split-Path -Parent $ScriptDir
$gradlew = Join-Path $repoRoot 'gradlew.bat'
Require-Path $gradlew 'gradlew.bat'

$reposInit = Join-Path $ScriptDir 'gradle\liaocl-repos.init.gradle'
Require-Path $reposInit 'Gradle init script'

# KSP 2 fails when the project and GRADLE_USER_HOME are on different drives,
# so keep a dedicated Gradle home on the project's drive.
$gradleUserHome = Join-Path (Split-Path -Parent $repoRoot) '.gradle-home'
if ($cfg.PSObject.Properties['gradleUserHome'] -and -not [string]::IsNullOrWhiteSpace([string]$cfg.gradleUserHome)) {
    $gradleUserHome = [Environment]::ExpandEnvironmentVariables([string]$cfg.gradleUserHome)
}
if ([IO.Path]::GetPathRoot($gradleUserHome) -ne [IO.Path]::GetPathRoot($repoRoot)) {
    Fail "gradleUserHome must be on the same drive as the project (KSP 2 limitation): $gradleUserHome"
}
New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null

# Reuse the already-downloaded wrapper distribution from the default Gradle home when present.
$wrapperPropertiesPath = Join-Path $repoRoot 'gradle\wrapper\gradle-wrapper.properties'
if ((Test-Path -LiteralPath $wrapperPropertiesPath) -and
        (Get-Content -LiteralPath $wrapperPropertiesPath -Raw) -match 'gradle-([0-9.]+)-bin\.zip') {
    $gradleVersion = $Matches[1]
    $defaultDist = Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-$gradleVersion-bin"
    $targetDist = Join-Path $gradleUserHome "wrapper\dists\gradle-$gradleVersion-bin"
    if ((Test-Path -LiteralPath $defaultDist) -and -not (Test-Path -LiteralPath $targetDist)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetDist) | Out-Null
        Copy-Item -LiteralPath $defaultDist -Destination $targetDist -Recurse -Force
        Write-Host "[info] seeded Gradle $gradleVersion distribution into $gradleUserHome"
    }
}

$versionText = (& cmd /c "`"$javaExe`" -version 2>&1") -join "`n"
if ($versionText -notmatch 'version "(\d+)') {
    Fail "Cannot determine Java version from: $versionText"
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 21) {
    Fail "This project requires JDK 21+ (jvmToolchain 21), but '$javaHome' is Java $javaMajor. Update javaHome in $Config."
}

if (-not $OutputDir) {
    $OutputDir = Join-Path $repoRoot 'dist'
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

Write-Host "[info] repo:        $repoRoot"
Write-Host "[info] appId:       $appId"
Write-Host "[info] abi:         $abi"
Write-Host "[info] JDK:         $javaHome (Java $javaMajor)"
Write-Host "[info] Android SDK: $androidHome"
Write-Host "[info] Gradle home: $gradleUserHome"
Write-Host "[info] keystore:    $storeFile"

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome
$env:ANDROID_SDK_ROOT = $androidHome
$env:GRADLE_USER_HOME = $gradleUserHome
$defaultGradleCache = Join-Path $env:USERPROFILE '.gradle\caches'
if (Test-Path -LiteralPath $defaultGradleCache) {
    $env:GRADLE_RO_DEP_CACHE = $defaultGradleCache
}
$env:PATH = (Join-Path $javaHome 'bin') + ';' + (Join-Path $androidHome 'platform-tools') + ';' + $env:PATH
$env:ORG_GRADLE_PROJECT_RELEASE_STORE_FILE = $storeFile
$env:ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD = [string]$cfg.storePassword
$env:ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS = [string]$cfg.keyAlias
$env:ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD = [string]$cfg.keyPassword

Push-Location $repoRoot
try {
    & $gradlew ':app:assembleAppRelease' `
        "-PcustomApplicationId=$appId" `
        "-PcustomAbiFilters=$abi" `
        '-Dorg.gradle.internal.http.connectionTimeout=120000' `
        '-Dorg.gradle.internal.http.socketTimeout=120000' `
        '-I' $reposInit `
        '--no-daemon' `
        '--console=plain' `
        '--stacktrace'
    if ($LASTEXITCODE -ne 0) {
        Fail "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$apkDir = Join-Path $repoRoot 'app\build\outputs\apk\app\release'
$metadataPath = Join-Path $apkDir 'output-metadata.json'
if (-not (Test-Path -LiteralPath $metadataPath)) {
    Fail "output-metadata.json not found: $metadataPath"
}
$metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
$element = @($metadata.elements)[0]
if (-not $element) {
    Fail "No APK entry in $metadataPath"
}

$versionName = [string]$element.versionName
$sourceApk = Join-Path $apkDir ([string]$element.outputFile)
Require-Path $sourceApk 'Built APK'

$abiLabel = $abi -replace '\s*,\s*', '-'
$targetApk = Join-Path $OutputDir ("liaocl-legado-{0}-{1}.apk" -f $versionName, $abiLabel)

if (-not $SkipVerify) {
    $buildTools = Join-Path $androidHome 'build-tools'
    $aapt2 = Get-ChildItem -Path $buildTools -Filter 'aapt2.exe' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $aapt2) {
        Fail "aapt2 not found under $buildTools; pass -SkipVerify to skip APK verification"
    }

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $badging = & $aapt2.FullName dump badging $sourceApk 2>&1 | ForEach-Object { "$_" }
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }

    $packageLine = $badging | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
    $nativeLine = $badging | Where-Object { $_ -like 'native-code:*' } | Select-Object -First 1

    if ($packageLine -notmatch ("name='" + [regex]::Escape($appId) + "'")) {
        Fail "APK applicationId mismatch. Expected '$appId', got: $packageLine"
    }

    $nativeAbis = @()
    if ($nativeLine -match "native-code: '([^']*)'") {
        $nativeAbis = @($Matches[1] -split '\s+' | Where-Object { $_ })
    }
    $expectedAbis = @($abi -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ((($nativeAbis | Sort-Object) -join ',') -ne (($expectedAbis | Sort-Object) -join ',')) {
        Fail "APK ABI mismatch. Expected '$($expectedAbis -join ',')', got: $($nativeAbis -join ',')"
    }
    Write-Host "[ok] package: $appId; native-code: $($nativeAbis -join ',')"

    $apksigner = Get-ChildItem -Path $buildTools -Filter 'apksigner.bat' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $apksigner) {
        Fail "apksigner not found under $buildTools; pass -SkipVerify to skip signature verification"
    }

    $ErrorActionPreference = 'Continue'
    try {
        & $apksigner.FullName verify --print-certs $sourceApk | Out-Null
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($LASTEXITCODE -ne 0) {
        Fail 'APK signature verification failed'
    }
    Write-Host '[ok] signature verified'
}

Copy-Item -LiteralPath $sourceApk -Destination $targetApk -Force

$hash = (Get-FileHash -LiteralPath $targetApk -Algorithm SHA256).Hash
$sizeMb = [math]::Round((Get-Item -LiteralPath $targetApk).Length / 1MB, 2)
Write-Host ''
Write-Host "[done] $targetApk"
Write-Host "[done] versionName: $versionName"
Write-Host "[done] size: ${sizeMb} MB"
Write-Host "[done] sha256: $hash"
