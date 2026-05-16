param(
    [string]$Serial,
    [string]$GradleTask = "installDebug",
    [string]$PackageName = "com.example.ridescope",
    [string]$ActivityName = ".MainActivity"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-ProjectRoot {
    return Split-Path -Parent $PSScriptRoot
}

function Resolve-AndroidSdkPath {
    param([string]$ProjectRoot)

    $localPropertiesPath = Join-Path $ProjectRoot "local.properties"
    if (Test-Path $localPropertiesPath) {
        $sdkLine = Select-String -Path $localPropertiesPath -Pattern '^sdk\.dir=' | Select-Object -First 1
        if ($sdkLine) {
            $rawPath = $sdkLine.Line.Substring(8)
            $sdkPath = $rawPath -replace '\\\\', '\' -replace '\\:', ':'
            if (Test-Path $sdkPath) {
                return $sdkPath
            }
        }
    }

    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    throw "SDK Android non trovato. Configura local.properties o ANDROID_SDK_ROOT."
}

function Resolve-AdbPath {
    param([string]$SdkPath)

    $candidate = Join-Path $SdkPath "platform-tools\\adb.exe"
    if (Test-Path $candidate) {
        return $candidate
    }

    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($adbCommand) {
        return $adbCommand.Source
    }

    throw "adb.exe non trovato."
}

function Resolve-JavaHome {
    $candidates = @(
        $env:JAVA_HOME,
        "C:\\Program Files\\Android\\Android Studio\\jbr",
        "C:\\Program Files\\Android\\Android Studio\\jre"
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\\java.exe"))) {
            return $candidate
        }
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javaCommand) {
        return Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
    }

    throw "Java non trovato. Installa un JDK o Android Studio."
}

function Get-ConnectedDeviceSerials {
    param([string]$AdbPath)

    $output = & $AdbPath devices
    if ($LASTEXITCODE -ne 0) {
        throw "Impossibile leggere i dispositivi ADB."
    }

    $serials = @()
    foreach ($line in $output) {
        if ($line -match '^(\S+)\s+device$') {
            $serials += $matches[1]
        }
    }

    return @($serials)
}

function New-AdbArgs {
    param([string]$SelectedSerial)

    if ([string]::IsNullOrWhiteSpace($SelectedSerial)) {
        return @()
    }

    return @("-s", $SelectedSerial)
}

$projectRoot = Resolve-ProjectRoot
$sdkPath = Resolve-AndroidSdkPath -ProjectRoot $projectRoot
$adbPath = Resolve-AdbPath -SdkPath $sdkPath
$javaHome = Resolve-JavaHome

$deviceSerials = @(Get-ConnectedDeviceSerials -AdbPath $adbPath)
if ($deviceSerials.Count -eq 0) {
    throw "Nessun dispositivo fisico collegato e autorizzato."
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($deviceSerials.Count -gt 1) {
        throw "Piu dispositivi collegati: specifica -Serial <id>."
    }
    $Serial = $deviceSerials[0]
}
elseif ($deviceSerials -notcontains $Serial) {
    throw "Il dispositivo '$Serial' non e tra quelli collegati."
}

$env:JAVA_HOME = $javaHome
$javaBin = Join-Path $javaHome "bin"
if (-not (($env:Path -split ';') -contains $javaBin)) {
    $env:Path = "$javaBin;$env:Path"
}

$gradlewPath = Join-Path $projectRoot "gradlew.bat"
$componentName = "$PackageName/$ActivityName"
$adbArgs = New-AdbArgs -SelectedSerial $Serial

Write-Host "Device: $Serial"
Write-Host "Gradle task: $GradleTask"
Write-Host "Component: $componentName"

Push-Location $projectRoot
try {
    & $gradlewPath $GradleTask
    if ($LASTEXITCODE -ne 0) {
        throw "Build/install fallita."
    }

    & $adbPath @adbArgs shell am start -W -n $componentName
    if ($LASTEXITCODE -ne 0) {
        throw "Avvio activity fallito."
    }

    Start-Sleep -Seconds 2
    $activityDump = & $adbPath @adbArgs shell dumpsys activity activities
    $topResumed = $activityDump | Select-String -Pattern ("topResumedActivity=.*" + [regex]::Escape($componentName)) | Select-Object -First 1

    if ($topResumed) {
        Write-Host "App avviata correttamente sul dispositivo."
    }
    else {
        Write-Warning "APK installato e activity lanciata, ma il focus finale non e stato confermato."
    }
}
finally {
    Pop-Location
}
