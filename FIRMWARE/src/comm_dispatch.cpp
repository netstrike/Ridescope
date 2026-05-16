#include "comm_dispatch.h"

#include "axis_map.h"
#include "comm_json.h"
#include "messages.h"

namespace
{
    // Vincoli numerici riusabili della configurazione JSON.
    // Verifica che il valore resti nel range [0,1].
    bool validateUnitFloat(const char* key, float value, String& errorMessage)
    {
        if (value < 0.0f || value > 1.0f)
        {
            errorMessage = Messages::Protocol::campoIntervallo01(key);
            return false;
        }
        return true;
    }

    // Verifica che il valore sia non negativo.
    bool validatePositiveFloat(const char* key, float value, String& errorMessage)
    {
        if (value < 0.0f)
        {
            errorMessage = Messages::Protocol::campoMaggioreUgualeZero(key);
            return false;
        }
        return true;
    }

    bool readAxisMapField(const JsonObjectConst& root, const char* key, AxisRef& target, String& errorMessage)
    {
        String stringValue;
        const JsonFieldResult result = CommJson::readString(root, key, stringValue);
        if (CommJson::fieldInvalid(result, errorMessage, key, Messages::JsonTypes::kStringa)) return false;
        if (result != JsonFieldResult::Ok)
        {
            return true;
        }

        AxisRef axisRef;
        if (!AxisMap::parseAxisRef(stringValue, axisRef))
        {
            errorMessage = Messages::Protocol::campoAsseSensoreNonValido(key);
            return false;
        }

        target = axisRef;
        return true;
    }

}

namespace CommDispatch
{
    // Valida e applica i campi configurabili ricevuti dal protocollo BLE.
    bool applyConfigFromJson(const JsonObjectConst& root, AppConfig& config, String& errorMessage)
    {
        float floatValue = 0.0f;
        bool boolValue = false;
        uint32_t uintValue = 0;

        JsonFieldResult result = CommJson::readFloat(root, "complementary_alpha", floatValue);
        if (CommJson::fieldInvalid(result, errorMessage, "complementary_alpha", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok)
        {
            if (!validateUnitFloat("complementary_alpha", floatValue, errorMessage)) return false;
            config.complementaryAlpha = floatValue;
        }

        result = CommJson::readFloat(root, "accel_lpf_alpha", floatValue);
        if (CommJson::fieldInvalid(result, errorMessage, "accel_lpf_alpha", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok)
        {
            if (!validateUnitFloat("accel_lpf_alpha", floatValue, errorMessage)) return false;
            config.accelLpfAlpha = floatValue;
        }

        result = CommJson::readFloat(root, "gyro_lpf_alpha", floatValue);
        if (CommJson::fieldInvalid(result, errorMessage, "gyro_lpf_alpha", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok)
        {
            if (!validateUnitFloat("gyro_lpf_alpha", floatValue, errorMessage)) return false;
            config.gyroLpfAlpha = floatValue;
        }

        result = CommJson::readFloat(root, "accel_trust_min_g", floatValue);
        if (CommJson::fieldInvalid(result, errorMessage, "accel_trust_min_g", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok)
        {
            if (!validatePositiveFloat("accel_trust_min_g", floatValue, errorMessage)) return false;
            config.accelTrustMinG = floatValue;
        }

        result = CommJson::readFloat(root, "accel_trust_max_g", floatValue);
        if (CommJson::fieldInvalid(result, errorMessage, "accel_trust_max_g", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok)
        {
            if (!validatePositiveFloat("accel_trust_max_g", floatValue, errorMessage)) return false;
            config.accelTrustMaxG = floatValue;
        }

        result = CommJson::readFloat(root, "accel_trust_fade_span_g", floatValue);
        if (CommJson::fieldInvalid(result, errorMessage, "accel_trust_fade_span_g", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok)
        {
            if (!validatePositiveFloat("accel_trust_fade_span_g", floatValue, errorMessage)) return false;
            config.accelTrustFadeSpanG = floatValue;
        }

        if (config.accelTrustMaxG < config.accelTrustMinG)
        {
            errorMessage = Messages::Protocol::campoMaggioreUgualeAltro("accel_trust_max_g", "accel_trust_min_g");
            return false;
        }

        result = CommJson::readBool(root, "complementary_enabled", boolValue);
        if (CommJson::fieldInvalid(result, errorMessage, "complementary_enabled", Messages::JsonTypes::kBooleano)) return false;
        if (result == JsonFieldResult::Ok) config.complementaryEnabled = boolValue;

        result = CommJson::readBool(root, "accel_lpf_enabled", boolValue);
        if (CommJson::fieldInvalid(result, errorMessage, "accel_lpf_enabled", Messages::JsonTypes::kBooleano)) return false;
        if (result == JsonFieldResult::Ok) config.accelLpfEnabled = boolValue;

        result = CommJson::readBool(root, "gyro_lpf_enabled", boolValue);
        if (CommJson::fieldInvalid(result, errorMessage, "gyro_lpf_enabled", Messages::JsonTypes::kBooleano)) return false;
        if (result == JsonFieldResult::Ok) config.gyroLpfEnabled = boolValue;

        result = CommJson::readBool(root, "adaptive_accel_trust_enabled", boolValue);
        if (CommJson::fieldInvalid(result, errorMessage, "adaptive_accel_trust_enabled", Messages::JsonTypes::kBooleano)) return false;
        if (result == JsonFieldResult::Ok) config.adaptiveAccelTrustEnabled = boolValue;

        if (!readAxisMapField(root, "body_lateral_axis", config.axisMap.bodyLateralAxis, errorMessage)) return false;
        if (!readAxisMapField(root, "body_longitudinal_axis", config.axisMap.bodyLongitudinalAxis, errorMessage)) return false;
        if (!readAxisMapField(root, "body_vertical_axis", config.axisMap.bodyVerticalAxis, errorMessage)) return false;

        result = CommJson::readBool(root, "debug_serial", boolValue);
        if (CommJson::fieldInvalid(result, errorMessage, "debug_serial", Messages::JsonTypes::kBooleano)) return false;
        if (result == JsonFieldResult::Ok) config.debugSerial = boolValue;

        result = CommJson::readUInt(root, "telemetry_period_ms", uintValue);
        if (CommJson::fieldInvalid(result, errorMessage, "telemetry_period_ms", Messages::JsonTypes::kInteroSenzaSegno)) return false;
        if (result == JsonFieldResult::Ok)
        {
            if (uintValue < 20U)
            {
                errorMessage = Messages::Protocol::telemetryPeriodTroppoBasso();
                return false;
            }
            config.telemetryPeriodMs = uintValue;
        }

        if (!AxisMap::isAxisMapValid(config.axisMap))
        {
            errorMessage = Messages::Protocol::mappaAssiSensoreNonValida();
            return false;
        }

        return true;
    }

    // Aggiorna gli offset sensore ricevuti dal client.
    bool applyCalibrationFromJson(const JsonObjectConst& root, CalibrationData& calibration, String& errorMessage)
    {
        // Ogni campo e opzionale, ma almeno uno deve essere presente
        // per evitare POST vuoti che sembrino riusciti.
        float value = 0.0f;
        bool changed = false;

        JsonFieldResult result = CommJson::readFloat(root, "acc_offset_x", value);
        if (CommJson::fieldInvalid(result, errorMessage, "acc_offset_x", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok) { calibration.accelOffsetX = value; changed = true; }

        result = CommJson::readFloat(root, "acc_offset_y", value);
        if (CommJson::fieldInvalid(result, errorMessage, "acc_offset_y", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok) { calibration.accelOffsetY = value; changed = true; }

        result = CommJson::readFloat(root, "acc_offset_z", value);
        if (CommJson::fieldInvalid(result, errorMessage, "acc_offset_z", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok) { calibration.accelOffsetZ = value; changed = true; }

        result = CommJson::readFloat(root, "gyro_offset_x_dps", value);
        if (CommJson::fieldInvalid(result, errorMessage, "gyro_offset_x_dps", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok) { calibration.gyroOffsetXDegS = value; changed = true; }

        result = CommJson::readFloat(root, "gyro_offset_y_dps", value);
        if (CommJson::fieldInvalid(result, errorMessage, "gyro_offset_y_dps", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok) { calibration.gyroOffsetYDegS = value; changed = true; }

        result = CommJson::readFloat(root, "gyro_offset_z_dps", value);
        if (CommJson::fieldInvalid(result, errorMessage, "gyro_offset_z_dps", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok) { calibration.gyroOffsetZDegS = value; changed = true; }

        if (!changed)
        {
            errorMessage = Messages::Protocol::kNessunCampoCalibrazione;
            return false;
        }

        calibration.valid = true;
        return true;
    }

    // Aggiorna il riferimento zero logico del veicolo.
    bool applyReferenceFromJson(const JsonObjectConst& root, ReferenceData& reference, String& errorMessage)
    {
        // Anche qui accettiamo patch parziali dello zero utente.
        float value = 0.0f;
        bool changed = false;

        JsonFieldResult result = CommJson::readFloat(root, "roll_zero_deg", value);
        if (CommJson::fieldInvalid(result, errorMessage, "roll_zero_deg", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok) { reference.rollZeroDeg = value; changed = true; }

        result = CommJson::readFloat(root, "pitch_zero_deg", value);
        if (CommJson::fieldInvalid(result, errorMessage, "pitch_zero_deg", Messages::JsonTypes::kNumero)) return false;
        if (result == JsonFieldResult::Ok) { reference.pitchZeroDeg = value; changed = true; }

        if (!changed)
        {
            errorMessage = Messages::Protocol::kNessunCampoRiferimento;
            return false;
        }

        reference.valid = true;
        return true;
    }
}
