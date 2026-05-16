package com.example.ridescope.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class LiveTelemetryCompact(
    @SerialName("ts") val timestampMs: Long? = null,
    @SerialName("its") val imuTimestampTicks: Long? = null,
    @SerialName("r") val rollDeg: Double? = null,
    @SerialName("p") val pitchDeg: Double? = null,
    @SerialName("rr") val rawRollDeg: Double? = null,
    @SerialName("rp") val rawPitchDeg: Double? = null,
    @SerialName("ay") val longitudinalAccelG: Double? = null,
    @SerialName("itc") val imuTemperatureC: Double? = null,
    @SerialName("ir")
    @Serializable(with = FlexibleBooleanSerializer::class)
    val imuReady: Boolean? = null,
    @SerialName("git") val gpsTimeOfWeekMs: Long? = null,
    @SerialName("gok")
    @Serializable(with = FlexibleBooleanSerializer::class)
    val gpsFixUsable: Boolean? = null,
    @SerialName("gft") val gpsFixType: Int? = null,
    @SerialName("gsv") val gpsNumSv: Int? = null,
    @SerialName("glon") val gpsLongitudeDegE7: Long? = null,
    @SerialName("glat") val gpsLatitudeDegE7: Long? = null,
    @SerialName("gmsl") val gpsAltitudeMslMm: Long? = null,
    @SerialName("gha") val gpsHorizontalAccuracyMm: Long? = null,
    @SerialName("gvn") val gpsVelocityNorthMmS: Long? = null,
    @SerialName("gve") val gpsVelocityEastMmS: Long? = null,
    @SerialName("gvd") val gpsVelocityDownMmS: Long? = null,
    @SerialName("gsp") val gpsGroundSpeedMmS: Long? = null,
    @SerialName("ghm") val gpsHeadingMotionDegE5: Long? = null,
    @SerialName("gsa") val gpsSpeedAccuracyMmS: Long? = null,
    @SerialName("ghd") val gpsHeadingAccuracyDegE5: Long? = null,
)

@OptIn(ExperimentalSerializationApi::class)
object FlexibleBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("FlexibleBooleanSerializer richiede JsonDecoder")
        val element = jsonDecoder.decodeSerializableValue(JsonElement.serializer())

        if (element is JsonNull) {
            return null
        }

        val primitive = element as? JsonPrimitive
            ?: throw SerializationException("Valore booleano non valido: $element")

        return primitive.booleanOrNull
            ?: primitive.intOrNull?.let { it != 0 }
            ?: primitive.contentOrNull?.lowercase()?.let { value ->
                when (value) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
            }
            ?: throw SerializationException("Valore booleano non valido: $element")
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) {
            encoder.encodeNull()
            return
        }

        encoder.encodeBoolean(value)
    }
}

@Serializable
enum class SensorAxisToken(
    val wireValue: String,
    val physicalAxis: Char,
) {
    @SerialName("+x")
    PositiveX("+x", 'x'),

    @SerialName("-x")
    NegativeX("-x", 'x'),

    @SerialName("+y")
    PositiveY("+y", 'y'),

    @SerialName("-y")
    NegativeY("-y", 'y'),

    @SerialName("+z")
    PositiveZ("+z", 'z'),

    @SerialName("-z")
    NegativeZ("-z", 'z'),
    ;

    companion object {
        fun fromWireValue(value: String): SensorAxisToken = entries.first { it.wireValue == value }
    }
}

@Serializable
data class FirmwareConfig(
    @SerialName("complementary_alpha") val complementaryAlpha: Double = 0.98,
    @SerialName("complementary_enabled") val complementaryEnabled: Boolean = true,
    @SerialName("accel_lpf_enabled") val accelLpfEnabled: Boolean = true,
    @SerialName("gyro_lpf_enabled") val gyroLpfEnabled: Boolean = true,
    @SerialName("adaptive_accel_trust_enabled") val adaptiveAccelTrustEnabled: Boolean = true,
    @SerialName("accel_lpf_alpha") val accelLpfAlpha: Double = 0.15,
    @SerialName("gyro_lpf_alpha") val gyroLpfAlpha: Double = 0.20,
    @SerialName("accel_trust_min_g") val accelTrustMinG: Double = 0.90,
    @SerialName("accel_trust_max_g") val accelTrustMaxG: Double = 1.10,
    @SerialName("accel_trust_fade_span_g") val accelTrustFadeSpanG: Double = 0.25,
    @SerialName("body_lateral_axis") val bodyLateralAxis: SensorAxisToken = SensorAxisToken.PositiveX,
    @SerialName("body_longitudinal_axis") val bodyLongitudinalAxis: SensorAxisToken = SensorAxisToken.PositiveY,
    @SerialName("body_vertical_axis") val bodyVerticalAxis: SensorAxisToken = SensorAxisToken.PositiveZ,
    @SerialName("telemetry_period_ms") val telemetryPeriodMs: Int = 100,
    @SerialName("debug_serial") val debugSerial: Boolean = true,
) {
    fun hasValidAxisMap(): Boolean = setOf(
        bodyLateralAxis.physicalAxis,
        bodyLongitudinalAxis.physicalAxis,
        bodyVerticalAxis.physicalAxis,
    ).size == 3
}

@Serializable
data class CalibrationStatus(
    @SerialName("acc_offset_x") val accOffsetX: Double? = null,
    @SerialName("acc_offset_y") val accOffsetY: Double? = null,
    @SerialName("acc_offset_z") val accOffsetZ: Double? = null,
    @SerialName("gyro_offset_x_dps") val gyroOffsetXDps: Double? = null,
    @SerialName("gyro_offset_y_dps") val gyroOffsetYDps: Double? = null,
    @SerialName("gyro_offset_z_dps") val gyroOffsetZDps: Double? = null,
    @SerialName("valid") val valid: Boolean = false,
    @SerialName("running") val running: Boolean = false,
    @SerialName("progress_percent") val progressPercent: Int = 0,
)

@Serializable
data class ReferenceStatus(
    @SerialName("roll_zero_deg") val rollZeroDeg: Double? = null,
    @SerialName("pitch_zero_deg") val pitchZeroDeg: Double? = null,
    @SerialName("valid") val valid: Boolean = false,
)

@Serializable
data class FirmwareIdentity(
    @SerialName("build") val build: String = "",
    @SerialName("timestamp") val timestamp: String = "",
    @SerialName("protocol") val protocol: String = "",
)

@Serializable
data class FirmwareBoardInfo(
    @SerialName("profile") val profile: String = "",
    @SerialName("mcu") val mcu: String = "",
    @SerialName("partition_profile") val partitionProfile: String = "",
    @SerialName("sensor_board") val sensorBoard: String = "",
    @SerialName("sensor_chip") val sensorChip: String = "",
)

@Serializable
data class FirmwareTransportInfo(
    @SerialName("kind") val kind: String = "ble",
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("service_uuid") val serviceUuid: String? = null,
    @SerialName("command_rx_uuid") val commandRxUuid: String? = null,
    @SerialName("response_tx_uuid") val responseTxUuid: String? = null,
    @SerialName("live_tx_uuid") val liveTxUuid: String? = null,
    @SerialName("ota_data_rx_uuid") val otaDataRxUuid: String? = null,
    @SerialName("pairing_required") val pairingRequired: Boolean = false,
    @SerialName("pairing_mode") val pairingMode: String? = null,
    @SerialName("ota_ble_enabled") val otaBleEnabled: Boolean = false,
)

@Serializable
data class FirmwareInfo(
    @SerialName("firmware") val firmware: FirmwareIdentity = FirmwareIdentity(),
    @SerialName("board") val board: FirmwareBoardInfo = FirmwareBoardInfo(),
    @SerialName("transport") val transport: FirmwareTransportInfo = FirmwareTransportInfo(),
)

@Serializable
data class FirmwareTransportStatus(
    @SerialName("kind") val kind: String = "ble",
    @SerialName("ble_ready") val bleReady: Boolean = false,
    @SerialName("ble_advertising") val bleAdvertising: Boolean = false,
    @SerialName("ble_client_connected") val bleClientConnected: Boolean = false,
    @SerialName("ble_authenticated") val bleAuthenticated: Boolean = false,
    @SerialName("ble_response_subscribed") val bleResponseSubscribed: Boolean = false,
    @SerialName("ble_live_subscribed") val bleLiveSubscribed: Boolean = false,
    @SerialName("ble_mtu") val bleMtu: Int = 0,
    @SerialName("ota_ble_enabled") val otaBleEnabled: Boolean = false,
    @SerialName("ota_busy") val otaBusy: Boolean = false,
    @SerialName("ota_progress_percent") val otaProgressPercent: Int = 0,
)

@Serializable
data class FirmwareFilterStatus(
    @SerialName("complementary_enabled") val complementaryEnabled: Boolean = false,
    @SerialName("accel_lpf_enabled") val accelLpfEnabled: Boolean = false,
    @SerialName("gyro_lpf_enabled") val gyroLpfEnabled: Boolean = false,
    @SerialName("adaptive_accel_trust_enabled") val adaptiveAccelTrustEnabled: Boolean = false,
)

@Serializable
data class FirmwareStatus(
    @SerialName("transport") val transport: FirmwareTransportStatus = FirmwareTransportStatus(),
    @SerialName("calibration_running") val calibrationRunning: Boolean = false,
    @SerialName("calibration_progress_percent") val calibrationProgressPercent: Int = 0,
    @SerialName("calibration_valid") val calibrationValid: Boolean = false,
    @SerialName("reference_valid") val referenceValid: Boolean = false,
    @SerialName("filters") val filters: FirmwareFilterStatus = FirmwareFilterStatus(),
    @SerialName("uptime_ms") val uptimeMs: Long = 0L,
    @SerialName("imu_ready") val imuReady: Boolean? = null,
    @SerialName("gps_present") val gpsPresent: Boolean = false,
    @SerialName("gps_configured") val gpsConfigured: Boolean = false,
    @SerialName("gps_streaming") val gpsStreaming: Boolean = false,
    @SerialName("gps_usable") val gpsUsable: Boolean = false,
    @SerialName("gps_fix_type") val gpsFixType: Int = 0,
    @SerialName("gps_num_sv") val gpsNumSv: Int = 0,
    @SerialName("gps_fix_age_ms") val gpsFixAgeMs: Long = 0L,
    @SerialName("i2c_address") val i2cAddress: String? = null,
    @SerialName("data_ready_mode") val dataReadyMode: String? = null,
    @SerialName("data_ready_now") val dataReadyNow: Boolean = false,
)

@Serializable
data class FirmwareOtaStatus(
    @SerialName("ota_ble_enabled") val otaBleEnabled: Boolean = false,
    @SerialName("ota_busy") val otaBusy: Boolean = false,
    @SerialName("ota_success") val otaSuccess: Boolean = false,
    @SerialName("reboot_scheduled") val rebootScheduled: Boolean = false,
    @SerialName("ota_progress_percent") val otaProgressPercent: Int = 0,
    @SerialName("expected_size") val expectedSize: Long = 0L,
    @SerialName("written_bytes") val writtenBytes: Long = 0L,
    @SerialName("last_error") val lastError: String = "",
)
