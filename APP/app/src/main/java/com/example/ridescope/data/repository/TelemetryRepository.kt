package com.example.ridescope.data.repository

import com.example.ridescope.data.model.CalibrationStatus
import com.example.ridescope.data.model.FirmwareBoardInfo
import com.example.ridescope.data.model.FirmwareConfig
import com.example.ridescope.data.model.FirmwareIdentity
import com.example.ridescope.data.model.FirmwareInfo
import com.example.ridescope.data.model.FirmwareOtaStatus
import com.example.ridescope.data.model.FirmwareStatus
import com.example.ridescope.data.model.FirmwareTransportInfo
import com.example.ridescope.data.model.LiveTelemetryCompact
import com.example.ridescope.data.model.ReferenceStatus
import com.example.ridescope.data.model.TelemetryGpsSource
import com.example.ridescope.data.model.TelemetryUiState
import com.example.ridescope.data.network.BleConnectionState
import com.example.ridescope.data.network.FirmwareBleClient
import com.example.ridescope.data.network.FirmwareBleClient.ResponseFallbackMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TelemetryRepository(
    private val bleClient: FirmwareBleClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun ensureTransportStarted() {
        bleClient.start()
    }

    fun isBleConnected(): Boolean = bleClient.connectionState.value.connected

    fun shutdownTransport() {
        bleClient.close()
    }

    fun connectLive(initialState: TelemetryUiState = TelemetryUiState(bleReconnecting = true)): Flow<TelemetryUiState> {
        bleClient.start()
        return merge(
            bleClient.connectionState.map(LiveStreamEvent::ConnectionChanged),
            bleClient.liveFrames.map(LiveStreamEvent::Frame),
        ).runningFold(initialState) { previous, event ->
            when (event) {
                is LiveStreamEvent.ConnectionChanged -> previous.copy(
                    bleConnected = event.state.connected,
                    bleReconnecting = event.state.reconnecting || event.state.scanning || event.state.connecting,
                    bleAuthenticated = event.state.authenticated,
                    liveSubscribed = event.state.liveSubscribed,
                    rawFrame = event.state.lastError ?: previous.rawFrame,
                )

                is LiveStreamEvent.Frame -> {
                    val frame = parseLiveTelemetry(event.rawJson)
                    if (frame == null) {
                        previous.copy(
                            bleConnected = true,
                            bleReconnecting = false,
                            liveSubscribed = true,
                            rawFrame = event.rawJson,
                        )
                    } else {
                        val nextRollDeg = frame.rollDeg ?: previous.rollDeg
                        val nextPitchDeg = frame.pitchDeg ?: previous.pitchDeg
                        val nextAccelG = frame.longitudinalAccelG ?: previous.longitudinalAccelG
                        val maxRollLeftDeg = minOf(previous.maxRollLeftDeg, nextRollDeg.coerceAtMost(0.0))
                        val maxRollRightDeg = maxOf(previous.maxRollRightDeg, nextRollDeg.coerceAtLeast(0.0))
                        val maxPitchDeg = maxOf(previous.maxPitchDeg, nextPitchDeg.coerceAtLeast(0.0))
                        val baseState = previous.copy(
                            bleConnected = true,
                            bleReconnecting = false,
                            bleAuthenticated = previous.bleAuthenticated,
                            liveSubscribed = true,
                            rollDeg = nextRollDeg,
                            pitchDeg = nextPitchDeg,
                            rawRollDeg = frame.rawRollDeg ?: previous.rawRollDeg,
                            rawPitchDeg = frame.rawPitchDeg ?: previous.rawPitchDeg,
                            maxPitchDeg = maxPitchDeg,
                            maxRollLeftDeg = maxRollLeftDeg,
                            maxRollRightDeg = maxRollRightDeg,
                            longitudinalAccelG = nextAccelG,
                            imuReady = frame.imuReady ?: previous.imuReady,
                            imuTimestampTicks = frame.imuTimestampTicks ?: previous.imuTimestampTicks,
                            imuTemperatureC = frame.imuTemperatureC ?: previous.imuTemperatureC,
                            rawFrame = event.rawJson,
                        )

                        baseState.withFirmwareGps(frame, previous)
                    }
                }
            }
        }
    }

    suspend fun readConfig(): FirmwareConfig =
        decodeCommandResponse(FirmwareConfig.serializer(), bleClient.sendCommand("get_config"))

    suspend fun writeConfig(
        config: FirmwareConfig,
        baseConfig: FirmwareConfig,
    ): FirmwareConfig {
        if (config == baseConfig) {
            return config
        }

        return decodeCommandResponse(
            FirmwareConfig.serializer(),
            bleClient.sendCommand("set_config") { putEditableFieldsDiff(config, baseConfig) },
        )
    }

    suspend fun readInfo(): FirmwareInfo {
        val rawJson = bleClient.sendCommand("get_info")
        ensureSuccessfulResponse(rawJson)
        return parseFirmwareInfo(rawJson)
    }

    suspend fun readStatus(): FirmwareStatus =
        decodeCommandResponse(FirmwareStatus.serializer(), bleClient.sendCommand("get_status"))

    suspend fun readOtaStatus(): FirmwareOtaStatus =
        decodeCommandResponse(
            FirmwareOtaStatus.serializer(),
            bleClient.sendCommand("get_ota_status", fallbackMode = ResponseFallbackMode.OtaStatus),
        )

    suspend fun readCalibration(): CalibrationStatus =
        decodeCommandResponse(CalibrationStatus.serializer(), bleClient.sendCommand("get_calibration"))

    suspend fun runCalibration() {
        ensureSuccessfulResponse(
            bleClient.sendCommand("run_calibration") {
                putInt("samples", 300)
                putInt("delay_ms", 5)
            },
        )
    }

    suspend fun clearCalibration() {
        ensureSuccessfulResponse(bleClient.sendCommand("clear_calibration"))
    }

    suspend fun readReference(): ReferenceStatus =
        decodeCommandResponse(ReferenceStatus.serializer(), bleClient.sendCommand("get_reference"))

    suspend fun zeroReference() {
        ensureSuccessfulResponse(
            bleClient.sendCommand("set_zero") {
                putString("axes", "both")
            },
        )
    }

    suspend fun clearReference() {
        ensureSuccessfulResponse(bleClient.sendCommand("clear_reference"))
    }

    suspend fun performOtaUpdate(
        firmwareBinary: ByteArray,
        onProgress: (writtenBytes: Int, totalBytes: Int) -> Unit = { _, _ -> },
    ): FirmwareOtaStatus {
        val rawJson = bleClient.transferOtaFirmware(firmwareBinary, onProgress)
        ensureSuccessfulResponse(rawJson)
        return json.decodeFromString(FirmwareOtaStatus.serializer(), rawJson)
    }

    private fun parseLiveTelemetry(rawJson: String): ParsedLiveTelemetry? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        if (!root.isLiveTelemetryPayload()) {
            return null
        }

        val frame = json.decodeFromString(LiveTelemetryCompact.serializer(), rawJson)
        return ParsedLiveTelemetry(
            timestampMs = frame.timestampMs,
            rollDeg = frame.rollDeg,
            pitchDeg = frame.pitchDeg,
            rawRollDeg = frame.rawRollDeg,
            rawPitchDeg = frame.rawPitchDeg,
            longitudinalAccelG = frame.longitudinalAccelG,
            imuTimestampTicks = frame.imuTimestampTicks,
            imuTemperatureC = frame.imuTemperatureC,
            imuReady = frame.imuReady,
            gpsFixUsable = frame.gpsFixUsable,
            gpsLatitudeDeg = frame.gpsLatitudeDegE7?.toDegreesE7(),
            gpsLongitudeDeg = frame.gpsLongitudeDegE7?.toDegreesE7(),
            gpsHeadingDeg = frame.gpsHeadingMotionDegE5?.toDegreesE5(),
            gpsAltitudeMeters = frame.gpsAltitudeMslMm?.toMetersFromMillimeters(),
            gpsAccuracyMeters = frame.gpsHorizontalAccuracyMm?.toMetersFromMillimeters(),
            gpsSpeedKmh = frame.gpsGroundSpeedMmS?.toKmh(),
        )
    }

    private fun parseFirmwareInfo(rawJson: String): FirmwareInfo {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrElse {
            return FirmwareInfo()
        }
        val firmwareRoot = root["firmware"]?.jsonObject
        val boardRoot = root["board"]?.jsonObject
        val transportRoot = root["transport"]?.jsonObject

        return FirmwareInfo(
            firmware = FirmwareIdentity(
                build = firmwareRoot.stringValue("build"),
                timestamp = firmwareRoot.stringValue("timestamp"),
                protocol = firmwareRoot.stringValue("protocol"),
            ),
            board = FirmwareBoardInfo(
                profile = boardRoot.stringValue("profile"),
                mcu = boardRoot.stringValue("mcu"),
                partitionProfile = boardRoot.stringValue("partition_profile"),
                sensorBoard = boardRoot.stringValue("sensor_board"),
                sensorChip = boardRoot.stringValue("sensor_chip"),
            ),
            transport = FirmwareTransportInfo(
                kind = transportRoot.stringValue("kind").ifBlank { "ble" },
                deviceName = transportRoot.optionalStringValue("device_name"),
                serviceUuid = transportRoot.optionalStringValue("service_uuid"),
                commandRxUuid = transportRoot.optionalStringValue("command_rx_uuid"),
                responseTxUuid = transportRoot.optionalStringValue("response_tx_uuid"),
                liveTxUuid = transportRoot.optionalStringValue("live_tx_uuid"),
                otaDataRxUuid = transportRoot.optionalStringValue("ota_data_rx_uuid"),
                pairingRequired = transportRoot.booleanValue("pairing_required"),
                pairingMode = transportRoot.optionalStringValue("pairing_mode"),
                otaBleEnabled = transportRoot.booleanValue("ota_ble_enabled"),
            ),
        )
    }

    private fun <T> decodeCommandResponse(
        serializer: KSerializer<T>,
        rawJson: String,
    ): T {
        ensureSuccessfulResponse(rawJson)
        return json.decodeFromString(serializer, rawJson)
    }

    private fun ensureSuccessfulResponse(rawJson: String) {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return
        val status = root["status"]?.jsonPrimitive?.contentOrNull ?: return
        if (status == "errore") {
            error(root["error"]?.jsonPrimitive?.contentOrNull ?: "Errore firmware")
        }
    }
}

private fun JsonObjectBuilder.putEditableFieldsDiff(
    config: FirmwareConfig,
    baseConfig: FirmwareConfig,
) {
    if (config.complementaryAlpha != baseConfig.complementaryAlpha) {
        putDouble("complementary_alpha", config.complementaryAlpha)
    }
    if (config.complementaryEnabled != baseConfig.complementaryEnabled) {
        putBoolean("complementary_enabled", config.complementaryEnabled)
    }
    if (config.accelLpfEnabled != baseConfig.accelLpfEnabled) {
        putBoolean("accel_lpf_enabled", config.accelLpfEnabled)
    }
    if (config.gyroLpfEnabled != baseConfig.gyroLpfEnabled) {
        putBoolean("gyro_lpf_enabled", config.gyroLpfEnabled)
    }
    if (config.adaptiveAccelTrustEnabled != baseConfig.adaptiveAccelTrustEnabled) {
        putBoolean("adaptive_accel_trust_enabled", config.adaptiveAccelTrustEnabled)
    }
    if (config.accelLpfAlpha != baseConfig.accelLpfAlpha) {
        putDouble("accel_lpf_alpha", config.accelLpfAlpha)
    }
    if (config.gyroLpfAlpha != baseConfig.gyroLpfAlpha) {
        putDouble("gyro_lpf_alpha", config.gyroLpfAlpha)
    }
    if (config.accelTrustMinG != baseConfig.accelTrustMinG) {
        putDouble("accel_trust_min_g", config.accelTrustMinG)
    }
    if (config.accelTrustMaxG != baseConfig.accelTrustMaxG) {
        putDouble("accel_trust_max_g", config.accelTrustMaxG)
    }
    if (config.accelTrustFadeSpanG != baseConfig.accelTrustFadeSpanG) {
        putDouble("accel_trust_fade_span_g", config.accelTrustFadeSpanG)
    }
    if (config.bodyLateralAxis != baseConfig.bodyLateralAxis) {
        putString("body_lateral_axis", config.bodyLateralAxis.wireValue)
    }
    if (config.bodyLongitudinalAxis != baseConfig.bodyLongitudinalAxis) {
        putString("body_longitudinal_axis", config.bodyLongitudinalAxis.wireValue)
    }
    if (config.bodyVerticalAxis != baseConfig.bodyVerticalAxis) {
        putString("body_vertical_axis", config.bodyVerticalAxis.wireValue)
    }
    if (config.telemetryPeriodMs != baseConfig.telemetryPeriodMs) {
        putInt("telemetry_period_ms", config.telemetryPeriodMs)
    }
    if (config.debugSerial != baseConfig.debugSerial) {
        putBoolean("debug_serial", config.debugSerial)
    }
}

private fun JsonObjectBuilder.putBoolean(key: String, value: Boolean) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.putDouble(key: String, value: Double) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.putInt(key: String, value: Int) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.putString(key: String, value: String) {
    put(key, JsonPrimitive(value))
}

private fun JsonObject?.stringValue(key: String): String {
    return this?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
}

private fun JsonObject?.optionalStringValue(key: String): String? {
    return this?.get(key)?.jsonPrimitive?.contentOrNull
}

private fun JsonObject?.booleanValue(key: String): Boolean {
    val primitive = this?.get(key)?.jsonPrimitive ?: return false
    return primitive.booleanOrNull
        ?: primitive.intOrNull?.let { it != 0 }
        ?: primitive.contentOrNull?.lowercase()?.let { value ->
            when (value) {
                "true", "1" -> true
                "false", "0" -> false
                else -> false
            }
        }
        ?: false
}

private sealed interface LiveStreamEvent {
    data class ConnectionChanged(val state: BleConnectionState) : LiveStreamEvent
    data class Frame(val rawJson: String) : LiveStreamEvent
}

private data class ParsedLiveTelemetry(
    val timestampMs: Long? = null,
    val rollDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rawRollDeg: Double? = null,
    val rawPitchDeg: Double? = null,
    val longitudinalAccelG: Double? = null,
    val imuTimestampTicks: Long? = null,
    val imuTemperatureC: Double? = null,
    val imuReady: Boolean? = null,
    val gpsFixUsable: Boolean? = null,
    val gpsLatitudeDeg: Double? = null,
    val gpsLongitudeDeg: Double? = null,
    val gpsHeadingDeg: Double? = null,
    val gpsAltitudeMeters: Double? = null,
    val gpsAccuracyMeters: Double? = null,
    val gpsSpeedKmh: Double? = null,
)

private fun TelemetryUiState.withFirmwareGps(
    frame: ParsedLiveTelemetry,
    previous: TelemetryUiState,
): TelemetryUiState {
    if (frame.gpsFixUsable != true) {
        return copy(
            gpsSource = TelemetryGpsSource.None,
            gpsFixAvailable = false,
            gpsUsingNetworkProvider = false,
            gpsSampleTimestampMs = frame.timestampMs ?: previous.gpsSampleTimestampMs,
            gpsLatitudeDeg = null,
            gpsLongitudeDeg = null,
            gpsHeadingDeg = null,
            gpsSpeedKmh = 0.0,
            gpsAltitudeMeters = null,
            gpsAccuracyMeters = null,
        )
    }

    val speedKmh = (frame.gpsSpeedKmh ?: 0.0).coerceIn(0.0, 320.0)
    val sampleTimestampMs = frame.timestampMs ?: previous.gpsSampleTimestampMs
    val tripIncrementKm = incrementalTripKm(
        previousTimestampMs = previous.gpsSampleTimestampMs,
        currentTimestampMs = sampleTimestampMs,
        speedKmh = speedKmh,
    )

    return copy(
        gpsSource = TelemetryGpsSource.Firmware,
        gpsFixAvailable = true,
        gpsUsingNetworkProvider = false,
        gpsSampleTimestampMs = sampleTimestampMs,
        gpsLatitudeDeg = frame.gpsLatitudeDeg,
        gpsLongitudeDeg = frame.gpsLongitudeDeg,
        gpsHeadingDeg = frame.gpsHeadingDeg,
        gpsSpeedKmh = speedKmh,
        gpsMaxSpeedKmh = maxOf(previous.gpsMaxSpeedKmh, speedKmh),
        gpsTripKm = previous.gpsTripKm + tripIncrementKm,
        gpsAltitudeMeters = frame.gpsAltitudeMeters,
        gpsAccuracyMeters = frame.gpsAccuracyMeters,
    )
}

private fun incrementalTripKm(
    previousTimestampMs: Long?,
    currentTimestampMs: Long?,
    speedKmh: Double,
): Double {
    if (previousTimestampMs == null || currentTimestampMs == null) {
        return 0.0
    }
    val deltaMs = currentTimestampMs - previousTimestampMs
    if (deltaMs !in 50L..10_000L) {
        return 0.0
    }
    return speedKmh * (deltaMs / 3_600_000.0)
}

private fun Long.toDegreesE7(): Double = this / 10_000_000.0

private fun Long.toDegreesE5(): Double = this / 100_000.0

private fun Long.toMetersFromMillimeters(): Double = this / 1_000.0

private fun Long.toKmh(): Double = this * 0.0036

private fun kotlinx.serialization.json.JsonObject.isLiveTelemetryPayload(): Boolean {
    return this["ts"] != null &&
        this["r"] != null &&
        this["p"] != null &&
        this["rr"] != null &&
        this["rp"] != null &&
        this["ay"] != null &&
        this["ir"] != null
}
