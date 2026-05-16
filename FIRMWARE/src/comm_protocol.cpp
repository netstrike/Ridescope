#include "comm_protocol.h"

#include "axis_map.h"
#include "config.h"
#include "gps.h"
#include "messages.h"
#include "sensors.h"

namespace
{
    // La serializzazione resta manuale per tenere il payload sotto controllo
    // nei percorsi piu frequenti, come la telemetria live.
    // Esegue l'escape dei caratteri che romperebbero il JSON.
    String jsonEscape(const String& input)
    {
        // I payload vengono costruiti a mano per ridurre overhead e allocazioni.
        // Qui vengono trattati solo i caratteri che renderebbero il JSON non valido.
        String output;
        output.reserve(input.length() + 8);

        for (size_t i = 0; i < input.length(); ++i)
        {
            const char c = input[i];
            switch (c)
            {
                case '\\': output += "\\\\"; break;
                case '"': output += "\\\""; break;
                case '\b': output += "\\b"; break;
                case '\f': output += "\\f"; break;
                case '\n': output += "\\n"; break;
                case '\r': output += "\\r"; break;
                case '\t': output += "\\t"; break;
                default: output += c; break;
            }
        }

        return output;
    }

    // Racchiude una stringa in virgolette JSON dopo l'escape.
    String jsonStringLiteral(const String& value)
    {
        return String("\"") + jsonEscape(value) + "\"";
    }
}

namespace CommProtocol
{
    // Assembla il contenitore esterno condiviso da tutte le risposte strutturate.
    String buildEnvelope(const String& payloadType, const String& requestId, const String& status, const String& payload)
    {
        // Tutte le risposte strutturate condividono questa busta esterna.
        String json = "{\"type\":" + jsonStringLiteral(payloadType);
        if (requestId.length() > 0)
        {
            json += ",\"id\":" + jsonStringLiteral(requestId);
        }
        if (status.length() > 0)
        {
            json += ",\"status\":" + jsonStringLiteral(status);
        }
        if (payload.length() > 0)
        {
            json += ",";
            json += payload;
        }
        json += "}";
        return json;
    }

    // Costruisce il payload standard di errore del protocollo.
    String makeErrorJson(const String& requestId, const String& command, const String& message)
    {
        const String payload = "\"cmd\":" + jsonStringLiteral(command) + ",\"error\":" + jsonStringLiteral(message);
        return buildEnvelope("response", requestId, Messages::Protocol::kStatoErrore, payload);
    }

    // Espone metadati statici del firmware, della board e del trasporto BLE.
    String makeInfoJson(const String& requestId)
    {
        String payload;
        payload += "\"firmware\":{\"build\":";
        payload += jsonStringLiteral(FIRMWARE_BUILD);
        payload += ",\"timestamp\":";
        payload += jsonStringLiteral(FIRMWARE_TIMESTAMP);
        payload += ",\"protocol\":";
        payload += jsonStringLiteral(FIRMWARE_PROTOCOL);
        payload += "},\"board\":{\"profile\":";
        payload += jsonStringLiteral(BOARD_PROFILE);
        payload += ",\"mcu\":";
        payload += jsonStringLiteral(MCU_PROFILE);
        payload += ",\"partition_profile\":";
        payload += jsonStringLiteral(PARTITION_PROFILE);
        payload += ",\"sensor_board\":";
        payload += jsonStringLiteral(SENSOR_BOARD_PROFILE);
        payload += ",\"sensor_chip\":";
        payload += jsonStringLiteral(SENSOR_CHIP_PROFILE);
        payload += "},\"transport\":{\"kind\":\"ble\",\"device_name\":";
        payload += jsonStringLiteral(BLE_DEVICE_NAME);
        payload += ",\"service_uuid\":";
        payload += jsonStringLiteral(BLE_SERVICE_UUID);
        payload += ",\"command_rx_uuid\":";
        payload += jsonStringLiteral(BLE_COMMAND_RX_UUID);
        payload += ",\"response_tx_uuid\":";
        payload += jsonStringLiteral(BLE_RESPONSE_TX_UUID);
        payload += ",\"live_tx_uuid\":";
        payload += jsonStringLiteral(BLE_LIVE_TX_UUID);
        payload += ",\"ota_data_rx_uuid\":";
        payload += jsonStringLiteral(BLE_OTA_DATA_RX_UUID);
        payload += ",\"pairing_required\":true,\"pairing_mode\":\"static_passkey\",\"ota_ble_enabled\":true}";
        return buildEnvelope("info", requestId, Messages::Protocol::kStatoOk, payload);
    }

    // Espone lo stato runtime dell'aggiornamento firmware OTA via BLE.
    String makeOtaStatusJson(const CommTransportRuntime& runtime)
    {
        String payload = "\"ota_ble_enabled\":true,\"ota_busy\":";
        payload += runtime.updateInProgress ? "true" : "false";
        payload += ",\"ota_success\":";
        payload += runtime.updateSuccess ? "true" : "false";
        payload += ",\"reboot_scheduled\":";
        payload += runtime.rebootScheduled ? "true" : "false";
        payload += ",\"ota_progress_percent\":";
        payload += String(runtime.progressPercent);
        payload += ",\"expected_size\":";
        payload += String(static_cast<unsigned long>(runtime.expectedSize));
        payload += ",\"written_bytes\":";
        payload += String(static_cast<unsigned long>(runtime.writtenBytes));
        payload += ",\"last_error\":";
        payload += jsonStringLiteral(runtime.lastError);
        return buildEnvelope("ota_status", "", Messages::Protocol::kStatoOk, payload);
    }

    // Serializza lo snapshot runtime nel formato compact del protocollo.
    String makeRuntimeStateJson(const RuntimeState& runtimeState)
    {
        // Formato corto pensato per l'app durante lo streaming continuo,
        // limitato ai soli valori istantanei necessari.
        GpsFixData gpsFix {};
        const bool gpsHasFix = Gps::readFix(gpsFix);
        const bool gpsStreaming = Gps::streaming();
        if (!gpsHasFix || !gpsStreaming)
        {
            gpsFix = {};
        }

        String json = "{\"ts\":";
        json += String(runtimeState.timestampMs);
        json += ",\"its\":";
        json += String(static_cast<unsigned long>(runtimeState.imuTimestampTicks));
        json += ",\"r\":";
        json += String(runtimeState.rollDeg, 2);
        json += ",\"p\":";
        json += String(runtimeState.pitchDeg, 2);
        json += ",\"rr\":";
        json += String(runtimeState.rawRollDeg, 2);
        json += ",\"rp\":";
        json += String(runtimeState.rawPitchDeg, 2);
        json += ",\"ay\":";
        json += String(runtimeState.longitudinalAccelG, 3);
        json += ",\"itc\":";
        json += String(runtimeState.imuTemperatureC, 2);
        json += ",\"ir\":";
        json += runtimeState.imuReady ? "true" : "false";
        json += ",\"git\":";
        json += String(static_cast<unsigned long>(gpsFix.iTowMs));
        json += ",\"gok\":";
        json += (gpsHasFix && gpsStreaming && gpsFix.valid) ? "true" : "false";
        json += ",\"gft\":";
        json += String(static_cast<unsigned int>(gpsFix.fixType));
        json += ",\"gsv\":";
        json += String(static_cast<unsigned int>(gpsFix.numSv));
        json += ",\"glon\":";
        json += String(gpsFix.longitudeDegE7);
        json += ",\"glat\":";
        json += String(gpsFix.latitudeDegE7);
        json += ",\"gmsl\":";
        json += String(gpsFix.heightMslMm);
        json += ",\"gha\":";
        json += String(static_cast<unsigned long>(gpsFix.horizontalAccMm));
        json += ",\"gvn\":";
        json += String(gpsFix.velNorthMmS);
        json += ",\"gve\":";
        json += String(gpsFix.velEastMmS);
        json += ",\"gvd\":";
        json += String(gpsFix.velDownMmS);
        json += ",\"gsp\":";
        json += String(gpsFix.groundSpeedMmS);
        json += ",\"ghm\":";
        json += String(gpsFix.headingMotionDegE5);
        json += ",\"gsa\":";
        json += String(static_cast<unsigned long>(gpsFix.speedAccMmS));
        json += ",\"ghd\":";
        json += String(static_cast<unsigned long>(gpsFix.headingAccDegE5));
        json += "}";
        return json;
    }

    // Serializza gli offset sensore e l'eventuale avanzamento della calibrazione asincrona.
    String makeCalibrationJson(const CalibrationData& calibration, const String& requestId)
    {
        String payload = "\"acc_offset_x\":";
        payload += String(calibration.accelOffsetX, 6);
        payload += ",\"acc_offset_y\":";
        payload += String(calibration.accelOffsetY, 6);
        payload += ",\"acc_offset_z\":";
        payload += String(calibration.accelOffsetZ, 6);
        payload += ",\"gyro_offset_x_dps\":";
        payload += String(calibration.gyroOffsetXDegS, 6);
        payload += ",\"gyro_offset_y_dps\":";
        payload += String(calibration.gyroOffsetYDegS, 6);
        payload += ",\"gyro_offset_z_dps\":";
        payload += String(calibration.gyroOffsetZDegS, 6);
        payload += ",\"valid\":";
        payload += calibration.valid ? "true" : "false";
        payload += ",\"running\":";
        payload += Sensors::isCalibrationRunning() ? "true" : "false";
        payload += ",\"progress_percent\":";
        payload += String(Sensors::calibrationProgressPercent());
        return buildEnvelope("calibration", requestId, Messages::Protocol::kStatoOk, payload);
    }

    // Serializza lo zero logico applicato a roll e pitch.
    String makeReferenceJson(const ReferenceData& reference, const String& requestId)
    {
        String payload = "\"roll_zero_deg\":";
        payload += String(reference.rollZeroDeg, 3);
        payload += ",\"pitch_zero_deg\":";
        payload += String(reference.pitchZeroDeg, 3);
        payload += ",\"valid\":";
        payload += reference.valid ? "true" : "false";
        return buildEnvelope("reference", requestId, Messages::Protocol::kStatoOk, payload);
    }

    // Serializza la configurazione utente esposta all'app e lo stato BLE essenziale.
    String makeConfigJson(const AppConfig& config, const CommTransportRuntime& runtime, const String& requestId)
    {
        String payload = "\"complementary_alpha\":";
        payload += String(config.complementaryAlpha, 3);
        payload += ",\"complementary_enabled\":";
        payload += config.complementaryEnabled ? "true" : "false";
        payload += ",\"accel_lpf_enabled\":";
        payload += config.accelLpfEnabled ? "true" : "false";
        payload += ",\"gyro_lpf_enabled\":";
        payload += config.gyroLpfEnabled ? "true" : "false";
        payload += ",\"adaptive_accel_trust_enabled\":";
        payload += config.adaptiveAccelTrustEnabled ? "true" : "false";
        payload += ",\"accel_lpf_alpha\":";
        payload += String(config.accelLpfAlpha, 3);
        payload += ",\"gyro_lpf_alpha\":";
        payload += String(config.gyroLpfAlpha, 3);
        payload += ",\"accel_trust_min_g\":";
        payload += String(config.accelTrustMinG, 3);
        payload += ",\"accel_trust_max_g\":";
        payload += String(config.accelTrustMaxG, 3);
        payload += ",\"accel_trust_fade_span_g\":";
        payload += String(config.accelTrustFadeSpanG, 3);
        payload += ",\"body_lateral_axis\":";
        payload += jsonStringLiteral(AxisMap::toString(config.axisMap.bodyLateralAxis));
        payload += ",\"body_longitudinal_axis\":";
        payload += jsonStringLiteral(AxisMap::toString(config.axisMap.bodyLongitudinalAxis));
        payload += ",\"body_vertical_axis\":";
        payload += jsonStringLiteral(AxisMap::toString(config.axisMap.bodyVerticalAxis));
        payload += ",\"debug_serial\":";
        payload += config.debugSerial ? "true" : "false";
        payload += ",\"telemetry_period_ms\":";
        payload += String(config.telemetryPeriodMs);
        payload += ",\"transport\":{\"kind\":\"ble\",\"pairing_required\":true,\"ota_ble_enabled\":true,\"ble_client_connected\":";
        payload += runtime.bleClientConnected ? "true" : "false";
        payload += ",\"ble_authenticated\":";
        payload += runtime.bleClientAuthenticated ? "true" : "false";
        payload += ",\"ble_mtu\":";
        payload += String(runtime.bleMtu);
        payload += "}";
        return buildEnvelope("config", requestId, Messages::Protocol::kStatoOk, payload);
    }

    // Compone il quadro operativo completo usato per diagnostica e health check,
    // inclusa la presenza del modulo GPS opzionale quando rilevato su UART
    // e lo stato del parser NAV-PVT, mentre il live porta sempre i campi GPS compact.
    String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId)
    {
        // Lo status unisce stato configurato, stato runtime essenziale e health del trasporto.
        String i2cAddress = "0x00";
        GpsFixData gpsFix {};
        const bool gpsHasFix = Gps::readFix(gpsFix);
        const bool gpsStreaming = Gps::streaming();
        if (Sensors::getAddress() != 0)
        {
            char buffer[8];
            snprintf(buffer, sizeof(buffer), "0x%02x", Sensors::getAddress());
            i2cAddress = String(buffer);
        }

        String payload = "\"transport\":{\"kind\":\"ble\",\"ble_ready\":";
        payload += runtime.bleReady ? "true" : "false";
        payload += ",\"ble_advertising\":";
        payload += runtime.bleAdvertising ? "true" : "false";
        payload += ",\"ble_client_connected\":";
        payload += runtime.bleClientConnected ? "true" : "false";
        payload += ",\"ble_authenticated\":";
        payload += runtime.bleClientAuthenticated ? "true" : "false";
        payload += ",\"ble_response_subscribed\":";
        payload += runtime.bleResponseSubscribed ? "true" : "false";
        payload += ",\"ble_live_subscribed\":";
        payload += runtime.bleLiveSubscribed ? "true" : "false";
        payload += ",\"ble_mtu\":";
        payload += String(runtime.bleMtu);
        payload += ",\"ota_ble_enabled\":true,\"ota_busy\":";
        payload += runtime.updateInProgress ? "true" : "false";
        payload += ",\"ota_progress_percent\":";
        payload += String(runtime.progressPercent);
        payload += "},\"calibration_running\":";
        payload += Sensors::isCalibrationRunning() ? "true" : "false";
        payload += ",\"calibration_progress_percent\":";
        payload += String(Sensors::calibrationProgressPercent());
        payload += ",\"calibration_valid\":";
        payload += calibration.valid ? "true" : "false";
        payload += ",\"reference_valid\":";
        payload += reference.valid ? "true" : "false";
        payload += ",\"filters\":{\"complementary_enabled\":";
        payload += config.complementaryEnabled ? "true" : "false";
        payload += ",\"accel_lpf_enabled\":";
        payload += config.accelLpfEnabled ? "true" : "false";
        payload += ",\"gyro_lpf_enabled\":";
        payload += config.gyroLpfEnabled ? "true" : "false";
        payload += ",\"adaptive_accel_trust_enabled\":";
        payload += config.adaptiveAccelTrustEnabled ? "true" : "false";
        payload += "},\"uptime_ms\":";
        payload += String(millis());
        payload += ",\"imu_ready\":";
        payload += runtimeState.imuReady ? "true" : "false";
        payload += ",\"gps_present\":";
        payload += Gps::present() ? "true" : "false";
        payload += ",\"gps_configured\":";
        payload += Gps::configured() ? "true" : "false";
        payload += ",\"gps_streaming\":";
        payload += gpsStreaming ? "true" : "false";
        payload += ",\"gps_usable\":";
        payload += (gpsHasFix && gpsStreaming && gpsFix.valid) ? "true" : "false";
        payload += ",\"gps_fix_type\":";
        payload += String(static_cast<unsigned int>(gpsHasFix ? gpsFix.fixType : 0U));
        payload += ",\"gps_num_sv\":";
        payload += String(static_cast<unsigned int>(gpsHasFix ? gpsFix.numSv : 0U));
        payload += ",\"gps_fix_age_ms\":";
        payload += String(static_cast<unsigned long>(gpsHasFix ? Gps::fixAgeMs() : 0U));
        payload += ",\"i2c_address\":";
        payload += jsonStringLiteral(i2cAddress);
        payload += ",\"data_ready_mode\":";
        payload += jsonStringLiteral(Sensors::isInt1Enabled() ? "int1" : "polling");
        payload += ",\"data_ready_now\":";
        payload += Sensors::isDataReadyPending() ? "true" : "false";
        return buildEnvelope("status", requestId, Messages::Protocol::kStatoOk, payload);
    }
}
