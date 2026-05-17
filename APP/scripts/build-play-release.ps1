param(
    [string]$KeystorePath = "$env:USERPROFILE\RideScopeKeys\ridescope-upload.jks",
    [string]$UploadKeyAlias = "ridescope-upload",
    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr",
    [switch]$CreateKeystore
)

$ErrorActionPreference = "Stop"

function Convert-SecureStringToPlainText {
    param([securestring]$Value)

    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Read-Secret {
    param(
        [string]$EnvironmentVariableName,
        [string]$Prompt
    )

    $fromEnvironment = [Environment]::GetEnvironmentVariable($EnvironmentVariableName, "Process")
    if ([string]::IsNullOrWhiteSpace($fromEnvironment)) {
        $fromEnvironment = [Environment]::GetEnvironmentVariable($EnvironmentVariableName, "User")
    }
    if ([string]::IsNullOrWhiteSpace($fromEnvironment)) {
        $fromEnvironment = [Environment]::GetEnvironmentVariable($EnvironmentVariableName, "Machine")
    }
    if (-not [string]::IsNullOrWhiteSpace($fromEnvironment)) {
        return $fromEnvironment
    }

    $secureValue = Read-Host -Prompt $Prompt -AsSecureString
    Convert-SecureStringToPlainText -Value $secureValue
}

$repoAppRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$keystoreFullPath = [Environment]::ExpandEnvironmentVariables($KeystorePath)
$keytoolPath = Join-Path $JavaHome "bin\keytool.exe"
$buildStatePath = Join-Path $repoAppRoot "ridescope_app_build_state.properties"
$releaseBundleDir = Join-Path $repoAppRoot "app\build\outputs\bundle\release"
$canonicalBundlePath = Join-Path $releaseBundleDir "app-release.aab"

if (-not (Test-Path $JavaHome)) {
    throw "JAVA_HOME non trovato: $JavaHome"
}

if ($CreateKeystore -and -not (Test-Path $keystoreFullPath)) {
    if (-not (Test-Path $keytoolPath)) {
        throw "keytool.exe non trovato: $keytoolPath"
    }
    $keystoreDirectory = Split-Path -Parent $keystoreFullPath
    New-Item -ItemType Directory -Force -Path $keystoreDirectory | Out-Null
    & $keytoolPath `
        -genkeypair `
        -v `
        -keystore $keystoreFullPath `
        -alias $UploadKeyAlias `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000
}

if (-not (Test-Path $keystoreFullPath)) {
    throw "Keystore non trovato: $keystoreFullPath. Riesegui con -CreateKeystore oppure passa -KeystorePath."
}

$storePassword = Read-Secret `
    -EnvironmentVariableName "RIDESCOPE_RELEASE_STORE_PASSWORD" `
    -Prompt "Password keystore RideScope"
$keyPassword = Read-Secret `
    -EnvironmentVariableName "RIDESCOPE_RELEASE_KEY_PASSWORD" `
    -Prompt "Password upload key RideScope"

$env:JAVA_HOME = $JavaHome
$env:RIDESCOPE_RELEASE_STORE_FILE = $keystoreFullPath
$env:RIDESCOPE_RELEASE_STORE_PASSWORD = $storePassword
$env:RIDESCOPE_RELEASE_KEY_ALIAS = $UploadKeyAlias
$env:RIDESCOPE_RELEASE_KEY_PASSWORD = $keyPassword

Push-Location $repoAppRoot
try {
    .\gradlew.bat :app:bundleRelease
    if (-not (Test-Path $buildStatePath)) {
        throw "File metadati build non trovato: $buildStatePath"
    }
    if (-not (Test-Path $canonicalBundlePath)) {
        throw "AAB release non trovato: $canonicalBundlePath"
    }

    $buildState = @{}
    Get-Content $buildStatePath | ForEach-Object {
        if ($_ -match "^\s*([^#][^=]+)=(.*)$") {
            $buildState[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
    $versionName = $buildState["build"]
    $versionCode = $buildState["versionCode"]
    if ([string]::IsNullOrWhiteSpace($versionName) -or [string]::IsNullOrWhiteSpace($versionCode)) {
        throw "Metadati build incompleti in $buildStatePath"
    }

    $versionedBundleName = "app-release-v$versionName-code$versionCode.aab"
    $versionedBundlePath = Join-Path $releaseBundleDir $versionedBundleName
    Copy-Item -LiteralPath $canonicalBundlePath -Destination $versionedBundlePath -Force

    Write-Host ""
    Write-Host "AAB Play Console generato con versionCode $versionCode e versionName ${versionName}:"
    Write-Host $versionedBundlePath
    Write-Host ""
    Write-Host "Carica questo file versionato in Play Console. Ogni nuovo upload deve avere un versionCode mai usato prima."
} finally {
    Pop-Location
    Remove-Item Env:\RIDESCOPE_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\RIDESCOPE_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
}
