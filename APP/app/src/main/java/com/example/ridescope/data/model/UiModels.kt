package com.example.ridescope.data.model

import kotlin.math.abs

enum class RecordingStatus {
    Idle,
    Recording,
    Paused,
    AwaitingSaveName,
}

data class RecordingPeaks(
    val speedKmh: Double = 0.0,
    val altitudeMeters: Double = 0.0,
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val accelG: Double = 0.0,
    val decelG: Double = 0.0,
)

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.Idle,
    val totalDurationMs: Long = 0L,
    val travelDurationMs: Long = 0L,
    val tripKm: Double = 0.0,
    val peaks: RecordingPeaks = RecordingPeaks(),
    val startedAtEpochMs: Long? = null,
    val stoppedAtEpochMs: Long? = null,
    val startTripKm: Double? = null,
    val outputFileName: String = "",
    val titleDraft: String = "",
)

enum class LongitudinalAccelUnit {
    G,
    Ms2,
}

enum class TelemetryGpsSource {
    None,
    Firmware,
    Phone,
}

data class FirmwareUpdateSettings(
    val httpBaseUrl: String = "http://www.sparvieri.org/ridescope",
    val firmwareDirectory: String = "firmware",
    val appDirectory: String = "app",
    val checkIntervalMinutes: Int = 1440,
) {
    fun normalized(): FirmwareUpdateSettings {
        val normalizedBaseUrl = httpBaseUrl.trim().trimEnd('/').ifBlank { Default.httpBaseUrl }
        return copy(
            httpBaseUrl = normalizedBaseUrl,
            firmwareDirectory = firmwareDirectory.trim().trim('/').ifBlank { Default.firmwareDirectory },
            appDirectory = appDirectory.trim().trim('/').ifBlank { Default.appDirectory },
            checkIntervalMinutes = checkIntervalMinutes.takeIf { it in CheckIntervalOptions } ?: Default.checkIntervalMinutes,
        )
    }

    fun manifestRemoteUrl(): String {
        return remoteUrl("manifest.json")
    }

    fun firmwareBinaryRemoteUrl(): String {
        return remoteUrl("firmware.bin")
    }

    fun appManifestRemoteUrl(): String {
        return remoteUrl("manifest.json", appDirectory)
    }

    fun appBinaryRemoteUrl(): String {
        return remoteUrl("ridescope.apk", appDirectory)
    }

    fun isConfigured(): Boolean {
        return httpBaseUrl.trim().startsWith("http://") || httpBaseUrl.trim().startsWith("https://")
    }

    companion object {
        val CheckIntervalOptions = listOf(0, 1, 60, 360, 720, 1440, 2880, 4320, 10080)
        val Default = FirmwareUpdateSettings()
    }

    private fun remoteUrl(fileName: String, directoryValue: String = firmwareDirectory): String {
        val baseUrl = httpBaseUrl.trim().trimEnd('/')
        val directory = directoryValue.trim().trim('/').ifBlank { "" }
        return if (directory.isBlank()) {
            "$baseUrl/$fileName"
        } else {
            "$baseUrl/$directory/$fileName"
        }
    }
}

enum class FirmwareUpdateAvailability {
    Unknown,
    UpToDate,
    UpdateAvailable,
    Error,
}

data class FirmwareUpdateCheckState(
    val lastCheckedAtEpochMs: Long? = null,
    val currentBuild: String = "",
    val currentTimestamp: String = "",
    val currentProtocol: String = "",
    val remoteBuild: String = "",
    val remoteTimestamp: String = "",
    val remoteProtocol: String = "",
    val availability: FirmwareUpdateAvailability = FirmwareUpdateAvailability.Unknown,
    val statusMessage: String = "Controllo non eseguito",
    val appCurrentBuild: String = "",
    val appCurrentTimestamp: String = "",
    val appCurrentProtocol: String = "",
    val appRemoteBuild: String = "",
    val appRemoteTimestamp: String = "",
    val appRemoteProtocol: String = "",
    val appAvailability: FirmwareUpdateAvailability = FirmwareUpdateAvailability.Unknown,
    val appStatusMessage: String = "Controllo non eseguito",
    val updateInProgress: Boolean = false,
    val updateProgressPercent: Int = 0,
    val appUpdateInProgress: Boolean = false,
    val appUpdateProgressPercent: Int = 0,
)

data class TelemetryGaugeConfig(
    val rollLimitDeg: Int = 50,
    val pitchMaxDeg: Int = 90,
    val longitudinalAccelLimitG: Double = 1.0,
    val longitudinalAccelUnit: LongitudinalAccelUnit = LongitudinalAccelUnit.G,
    val speedMaxKmh: Int = 300,
) {
    fun normalized(): TelemetryGaugeConfig {
        return copy(
            rollLimitDeg = rollLimitDeg.takeIf { it in RollLimitOptions } ?: Default.rollLimitDeg,
            pitchMaxDeg = pitchMaxDeg.takeIf { it in PitchMaxOptions } ?: Default.pitchMaxDeg,
            longitudinalAccelLimitG = LongitudinalAccelLimitOptions.firstOrNull {
                abs(it - longitudinalAccelLimitG) < 0.001
            } ?: Default.longitudinalAccelLimitG,
            speedMaxKmh = speedMaxKmh.takeIf { it in SpeedMaxOptions } ?: Default.speedMaxKmh,
        )
    }

    companion object {
        val RollLimitOptions = listOf(40, 50, 60, 70, 80, 90)
        val PitchMaxOptions = listOf(45, 60, 75, 90, 105, 120)
        val LongitudinalAccelLimitOptions = listOf(0.5, 0.8, 1.0, 1.2, 1.5, 2.0)
        val SpeedMaxOptions = listOf(180, 210, 240, 270, 300, 330)
        val Default = TelemetryGaugeConfig()
    }
}

data class TelemetryUiState(
    val bleConnected: Boolean = false,
    val bleReconnecting: Boolean = false,
    val bleAuthenticated: Boolean = false,
    val liveSubscribed: Boolean = false,
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val rawRollDeg: Double = 0.0,
    val rawPitchDeg: Double = 0.0,
    val maxPitchDeg: Double = 0.0,
    val maxRollLeftDeg: Double = 0.0,
    val maxRollRightDeg: Double = 0.0,
    val longitudinalAccelG: Double = 0.0,
    val imuReady: Boolean = false,
    val imuTimestampTicks: Long? = null,
    val imuTemperatureC: Double? = null,
    val gpsPermissionGranted: Boolean = false,
    val gpsSource: TelemetryGpsSource = TelemetryGpsSource.None,
    val gpsFixAvailable: Boolean = false,
    val gpsUsingNetworkProvider: Boolean = false,
    val gpsSampleTimestampMs: Long? = null,
    val gpsLatitudeDeg: Double? = null,
    val gpsLongitudeDeg: Double? = null,
    val gpsHeadingDeg: Double? = null,
    val gpsSpeedKmh: Double = 0.0,
    val gpsMaxSpeedKmh: Double = 0.0,
    val gpsTripKm: Double = 0.0,
    val gpsAltitudeMeters: Double? = null,
    val gpsAccuracyMeters: Double? = null,
    val gaugeConfig: TelemetryGaugeConfig = TelemetryGaugeConfig(),
    val recording: RecordingUiState = RecordingUiState(),
    val rawFrame: String = "",
)

data class PhoneLocationSnapshot(
    val permissionGranted: Boolean = false,
    val fixAvailable: Boolean = false,
    val usingNetworkProvider: Boolean = false,
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val headingDeg: Double? = null,
    val speedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val tripKm: Double = 0.0,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Double? = null,
)
