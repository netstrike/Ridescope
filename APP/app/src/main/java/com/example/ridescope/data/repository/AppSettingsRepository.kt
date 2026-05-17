package com.example.ridescope.data.repository

import android.content.Context
import com.example.ridescope.data.model.FirmwareUpdateAvailability
import com.example.ridescope.data.model.FirmwareUpdateCheckState
import com.example.ridescope.data.model.FirmwareUpdateSettings
import com.example.ridescope.data.model.LongitudinalAccelUnit
import com.example.ridescope.data.model.TelemetryGaugeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettingsRepository(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val _telemetryGaugeConfig = MutableStateFlow(loadTelemetryGaugeConfig())
    val telemetryGaugeConfig: StateFlow<TelemetryGaugeConfig> = _telemetryGaugeConfig.asStateFlow()
    private val _keepScreenOnEnabled = MutableStateFlow(loadKeepScreenOnEnabled())
    val keepScreenOnEnabled: StateFlow<Boolean> = _keepScreenOnEnabled.asStateFlow()
    private val _firmwareUpdateSettings = MutableStateFlow(loadFirmwareUpdateSettings())
    val firmwareUpdateSettings: StateFlow<FirmwareUpdateSettings> = _firmwareUpdateSettings.asStateFlow()
    private val _firmwareUpdateCheckState = MutableStateFlow(loadFirmwareUpdateCheckState())
    val firmwareUpdateCheckState: StateFlow<FirmwareUpdateCheckState> = _firmwareUpdateCheckState.asStateFlow()

    fun currentTelemetryGaugeConfig(): TelemetryGaugeConfig = _telemetryGaugeConfig.value
    fun currentKeepScreenOnEnabled(): Boolean = _keepScreenOnEnabled.value
    fun currentFirmwareUpdateSettings(): FirmwareUpdateSettings = _firmwareUpdateSettings.value
    fun currentFirmwareUpdateCheckState(): FirmwareUpdateCheckState = _firmwareUpdateCheckState.value

    fun updateTelemetryGaugeConfig(config: TelemetryGaugeConfig) {
        val normalized = config.normalized()
        prefs.edit()
            .putInt(KeyRollLimitDeg, normalized.rollLimitDeg)
            .putInt(KeyPitchMaxDeg, normalized.pitchMaxDeg)
            .putString(KeyLongitudinalAccelLimitG, normalized.longitudinalAccelLimitG.toString())
            .putString(KeyLongitudinalAccelUnit, normalized.longitudinalAccelUnit.name)
            .putInt(KeySpeedMaxKmh, normalized.speedMaxKmh)
            .apply()
        _telemetryGaugeConfig.value = normalized
    }

    fun updateKeepScreenOnEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KeyKeepScreenOnEnabled, enabled)
            .apply()
        _keepScreenOnEnabled.value = enabled
    }

    fun updateFirmwareUpdateSettings(settings: FirmwareUpdateSettings) {
        val normalized = settings.normalized()
        prefs.edit()
            .putString(KeyFirmwareUpdateHttpBaseUrl, normalized.httpBaseUrl)
            .putString(KeyFirmwareUpdateFirmwareDirectory, normalized.firmwareDirectory)
            .putString(KeyFirmwareUpdateAppDirectory, normalized.appDirectory)
            .putInt(KeyFirmwareUpdateCheckIntervalMinutes, normalized.checkIntervalMinutes)
            .apply()
        _firmwareUpdateSettings.value = normalized
    }

    fun updateFirmwareUpdateCheckState(state: FirmwareUpdateCheckState) {
        prefs.edit()
            .putLong(KeyFirmwareUpdateLastCheckedAtEpochMs, state.lastCheckedAtEpochMs ?: 0L)
            .putString(KeyFirmwareUpdateCurrentBuild, state.currentBuild)
            .putString(KeyFirmwareUpdateCurrentTimestamp, state.currentTimestamp)
            .putString(KeyFirmwareUpdateCurrentProtocol, state.currentProtocol)
            .putString(KeyFirmwareUpdateRemoteBuild, state.remoteBuild)
            .putString(KeyFirmwareUpdateRemoteTimestamp, state.remoteTimestamp)
            .putString(KeyFirmwareUpdateRemoteProtocol, state.remoteProtocol)
            .putString(KeyFirmwareUpdateAvailability, state.availability.name)
            .putString(KeyFirmwareUpdateStatusMessage, state.statusMessage)
            .putString(KeyAppUpdateCurrentBuild, state.appCurrentBuild)
            .putString(KeyAppUpdateCurrentTimestamp, state.appCurrentTimestamp)
            .putString(KeyAppUpdateCurrentProtocol, state.appCurrentProtocol)
            .putString(KeyAppUpdateRemoteBuild, state.appRemoteBuild)
            .putString(KeyAppUpdateRemoteTimestamp, state.appRemoteTimestamp)
            .putString(KeyAppUpdateRemoteProtocol, state.appRemoteProtocol)
            .putString(KeyAppUpdateAvailability, state.appAvailability.name)
            .putString(KeyAppUpdateStatusMessage, state.appStatusMessage)
            .putBoolean(KeyFirmwareUpdateInProgress, state.updateInProgress)
            .putInt(KeyFirmwareUpdateProgressPercent, state.updateProgressPercent)
            .putBoolean(KeyAppUpdateInProgress, state.appUpdateInProgress)
            .putInt(KeyAppUpdateProgressPercent, state.appUpdateProgressPercent)
            .apply()
        _firmwareUpdateCheckState.value = state
    }

    private fun loadTelemetryGaugeConfig(): TelemetryGaugeConfig {
        val loaded = TelemetryGaugeConfig(
            rollLimitDeg = prefs.getInt(KeyRollLimitDeg, TelemetryGaugeConfig.Default.rollLimitDeg),
            pitchMaxDeg = prefs.getInt(KeyPitchMaxDeg, TelemetryGaugeConfig.Default.pitchMaxDeg),
            longitudinalAccelLimitG = prefs.getString(
                KeyLongitudinalAccelLimitG,
                TelemetryGaugeConfig.Default.longitudinalAccelLimitG.toString(),
            )?.toDoubleOrNull() ?: TelemetryGaugeConfig.Default.longitudinalAccelLimitG,
            longitudinalAccelUnit = prefs.getString(
                KeyLongitudinalAccelUnit,
                TelemetryGaugeConfig.Default.longitudinalAccelUnit.name,
            )?.let { storedValue ->
                LongitudinalAccelUnit.entries.firstOrNull { it.name == storedValue }
            } ?: TelemetryGaugeConfig.Default.longitudinalAccelUnit,
            speedMaxKmh = prefs.getInt(KeySpeedMaxKmh, TelemetryGaugeConfig.Default.speedMaxKmh),
        )
        return loaded.normalized()
    }

    private fun loadKeepScreenOnEnabled(): Boolean {
        return prefs.getBoolean(KeyKeepScreenOnEnabled, true)
    }

    private fun loadFirmwareUpdateSettings(): FirmwareUpdateSettings {
        val storedBaseUrl = prefs.getString(KeyFirmwareUpdateHttpBaseUrl, null)
            ?.takeIf { it.isNotBlank() }
        val migratedBaseUrl = when (storedBaseUrl?.trim()?.trimEnd('/')) {
            null -> FirmwareUpdateSettings.Default.httpBaseUrl
            LegacyFirmwareUpdateHttpBaseUrl -> FirmwareUpdateSettings.Default.httpBaseUrl
            else -> storedBaseUrl
        }
        val legacyFirmwareDirectory = prefs.getString(
            KeyFirmwareUpdateFtpDirectory,
            null,
        )?.takeIf { it.isNotBlank() }
        val legacyAppDirectory = prefs.getString(
            KeyFirmwareUpdateAppFtpDirectory,
            null,
        )?.takeIf { it.isNotBlank() }

        val migratedFirmwareDirectory = when (legacyFirmwareDirectory) {
            null -> FirmwareUpdateSettings.Default.firmwareDirectory
            LegacyFirmwareUpdateFtpDirectory,
            "ftp/ridescope/firmware",
            "firmware",
            "/firmware",
            -> FirmwareUpdateSettings.Default.firmwareDirectory
            else -> legacyFirmwareDirectory.substringAfterLast('/').ifBlank { FirmwareUpdateSettings.Default.firmwareDirectory }
        }
        val migratedAppDirectory = when (legacyAppDirectory) {
            null -> FirmwareUpdateSettings.Default.appDirectory
            "ftp/ridescope/app",
            "app",
            "/app",
            -> FirmwareUpdateSettings.Default.appDirectory
            else -> legacyAppDirectory.substringAfterLast('/').ifBlank { FirmwareUpdateSettings.Default.appDirectory }
        }

        val storedMinutes = when {
            prefs.contains(KeyFirmwareUpdateCheckIntervalMinutes) ->
                prefs.getInt(KeyFirmwareUpdateCheckIntervalMinutes, FirmwareUpdateSettings.Default.checkIntervalMinutes)
            prefs.contains(KeyFirmwareUpdateCheckIntervalHours) ->
                prefs.getInt(KeyFirmwareUpdateCheckIntervalHours, FirmwareUpdateSettings.Default.checkIntervalMinutes / 60) * 60
            else -> FirmwareUpdateSettings.Default.checkIntervalMinutes
        }

        val settings = FirmwareUpdateSettings(
            httpBaseUrl = migratedBaseUrl,
            firmwareDirectory = prefs.getString(
                KeyFirmwareUpdateFirmwareDirectory,
                migratedFirmwareDirectory,
            )?.takeIf { it.isNotBlank() } ?: migratedFirmwareDirectory,
            appDirectory = prefs.getString(
                KeyFirmwareUpdateAppDirectory,
                migratedAppDirectory,
            )?.takeIf { it.isNotBlank() } ?: migratedAppDirectory,
            checkIntervalMinutes = storedMinutes,
        ).normalized()

        if (storedBaseUrl?.trim()?.trimEnd('/') != migratedBaseUrl ||
            !prefs.contains(KeyFirmwareUpdateFirmwareDirectory) ||
            !prefs.contains(KeyFirmwareUpdateAppDirectory) ||
            !prefs.contains(KeyFirmwareUpdateCheckIntervalMinutes)
        ) {
            prefs.edit()
                .putString(KeyFirmwareUpdateHttpBaseUrl, settings.httpBaseUrl)
                .putString(KeyFirmwareUpdateFirmwareDirectory, settings.firmwareDirectory)
                .putString(KeyFirmwareUpdateAppDirectory, settings.appDirectory)
                .putInt(KeyFirmwareUpdateCheckIntervalMinutes, settings.checkIntervalMinutes)
                .apply()
        }

        return settings
    }

    private fun loadFirmwareUpdateCheckState(): FirmwareUpdateCheckState {
        val storedAvailability = prefs.getString(
            KeyFirmwareUpdateAvailability,
            FirmwareUpdateAvailability.Unknown.name,
        )
        val storedAppAvailability = prefs.getString(
            KeyAppUpdateAvailability,
            FirmwareUpdateAvailability.Unknown.name,
        )
        return FirmwareUpdateCheckState(
            lastCheckedAtEpochMs = prefs.getLong(KeyFirmwareUpdateLastCheckedAtEpochMs, 0L).takeIf { it > 0L },
            currentBuild = prefs.getString(KeyFirmwareUpdateCurrentBuild, "").orEmpty(),
            currentTimestamp = prefs.getString(KeyFirmwareUpdateCurrentTimestamp, "").orEmpty(),
            currentProtocol = prefs.getString(KeyFirmwareUpdateCurrentProtocol, "").orEmpty(),
            remoteBuild = prefs.getString(KeyFirmwareUpdateRemoteBuild, "").orEmpty(),
            remoteTimestamp = prefs.getString(KeyFirmwareUpdateRemoteTimestamp, "").orEmpty(),
            remoteProtocol = prefs.getString(KeyFirmwareUpdateRemoteProtocol, "").orEmpty(),
            availability = FirmwareUpdateAvailability.entries.firstOrNull { it.name == storedAvailability }
                ?: FirmwareUpdateAvailability.Unknown,
            statusMessage = prefs.getString(KeyFirmwareUpdateStatusMessage, "Controllo non eseguito").orEmpty(),
            appCurrentBuild = prefs.getString(KeyAppUpdateCurrentBuild, "").orEmpty(),
            appCurrentTimestamp = prefs.getString(KeyAppUpdateCurrentTimestamp, "").orEmpty(),
            appCurrentProtocol = prefs.getString(KeyAppUpdateCurrentProtocol, "").orEmpty(),
            appRemoteBuild = prefs.getString(KeyAppUpdateRemoteBuild, "").orEmpty(),
            appRemoteTimestamp = prefs.getString(KeyAppUpdateRemoteTimestamp, "").orEmpty(),
            appRemoteProtocol = prefs.getString(KeyAppUpdateRemoteProtocol, "").orEmpty(),
            appAvailability = FirmwareUpdateAvailability.entries.firstOrNull { it.name == storedAppAvailability }
                ?: FirmwareUpdateAvailability.Unknown,
            appStatusMessage = prefs.getString(KeyAppUpdateStatusMessage, "Controllo non eseguito").orEmpty(),
            updateInProgress = false,
            updateProgressPercent = 0,
            appUpdateInProgress = false,
            appUpdateProgressPercent = 0,
        )
    }

    private companion object {
        const val PrefsName = "ridescope_app_settings"
        const val KeyRollLimitDeg = "telemetry_gauge_roll_limit_deg"
        const val KeyPitchMaxDeg = "telemetry_gauge_pitch_max_deg"
        const val KeyLongitudinalAccelLimitG = "telemetry_gauge_longitudinal_accel_limit_g"
        const val KeyLongitudinalAccelUnit = "telemetry_gauge_longitudinal_accel_unit"
        const val KeySpeedMaxKmh = "telemetry_gauge_speed_max_kmh"
        const val KeyKeepScreenOnEnabled = "keep_screen_on_enabled"
        const val KeyFirmwareUpdateHttpBaseUrl = "firmware_update_http_base_url"
        const val KeyFirmwareUpdateFirmwareDirectory = "firmware_update_firmware_directory"
        const val KeyFirmwareUpdateAppDirectory = "firmware_update_app_directory"
        const val KeyFirmwareUpdateCheckIntervalMinutes = "firmware_update_check_interval_minutes"
        const val KeyFirmwareUpdateFtpHost = "firmware_update_ftp_host"
        const val KeyFirmwareUpdateFtpPort = "firmware_update_ftp_port"
        const val KeyFirmwareUpdateFtpDirectory = "firmware_update_ftp_directory"
        const val KeyFirmwareUpdateAppFtpDirectory = "firmware_update_app_ftp_directory"
        const val KeyFirmwareUpdateFtpUsername = "firmware_update_ftp_username"
        const val KeyFirmwareUpdateFtpPassword = "firmware_update_ftp_password"
        const val KeyFirmwareUpdateCheckIntervalHours = "firmware_update_check_interval_hours"
        const val KeyFirmwareUpdateLastCheckedAtEpochMs = "firmware_update_last_checked_at_epoch_ms"
        const val KeyFirmwareUpdateCurrentBuild = "firmware_update_current_build"
        const val KeyFirmwareUpdateCurrentTimestamp = "firmware_update_current_timestamp"
        const val KeyFirmwareUpdateCurrentProtocol = "firmware_update_current_protocol"
        const val KeyFirmwareUpdateRemoteBuild = "firmware_update_remote_build"
        const val KeyFirmwareUpdateRemoteTimestamp = "firmware_update_remote_timestamp"
        const val KeyFirmwareUpdateRemoteProtocol = "firmware_update_remote_protocol"
        const val KeyFirmwareUpdateAvailability = "firmware_update_availability"
        const val KeyFirmwareUpdateStatusMessage = "firmware_update_status_message"
        const val KeyAppUpdateCurrentBuild = "app_update_current_build"
        const val KeyAppUpdateCurrentTimestamp = "app_update_current_timestamp"
        const val KeyAppUpdateCurrentProtocol = "app_update_current_protocol"
        const val KeyAppUpdateRemoteBuild = "app_update_remote_build"
        const val KeyAppUpdateRemoteTimestamp = "app_update_remote_timestamp"
        const val KeyAppUpdateRemoteProtocol = "app_update_remote_protocol"
        const val KeyAppUpdateAvailability = "app_update_availability"
        const val KeyAppUpdateStatusMessage = "app_update_status_message"
        const val KeyFirmwareUpdateInProgress = "firmware_update_in_progress"
        const val KeyFirmwareUpdateProgressPercent = "firmware_update_progress_percent"
        const val KeyAppUpdateInProgress = "app_update_in_progress"
        const val KeyAppUpdateProgressPercent = "app_update_progress_percent"
        const val LegacyFirmwareUpdateHttpBaseUrl = "http://www.sparvieri.org/ridescope"
        const val LegacyFirmwareUpdateFtpDirectory = "ftp/rideshare/firmware"
    }
}
