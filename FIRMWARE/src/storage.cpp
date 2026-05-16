// Implementazione del layer NVS basato su Preferences.
// Tutte le chiavi sono raccolte qui per mantenere centralizzata la persistenza.

#include "axis_map.h"
#include "storage.h"
#include "config.h"
#include <Arduino.h>
#include <Preferences.h>

namespace
{
    Preferences g_preferences;

    void cleanupLegacyKeys()
    {
        g_preferences.remove("wifiEn");
        g_preferences.remove("wifiSsid");
        g_preferences.remove("wifiPass");
        g_preferences.remove("wifiIp");
        g_preferences.remove("dbgFakeTel");
    }
}

namespace Storage
{
    // Apre il namespace NVS del firmware in lettura/scrittura.
    void begin()
    {
        g_preferences.begin(NVS_NAMESPACE, false);
        cleanupLegacyKeys();
    }

    // Lettura di tutta la configurazione app persistente dal namespace NVS del progetto.
    void loadConfig(AppConfig& config)
    {
        config.complementaryAlpha = g_preferences.getFloat("compAlpha", config.complementaryAlpha);
        config.accelLpfAlpha = g_preferences.getFloat("accLpfA", config.accelLpfAlpha);
        config.gyroLpfAlpha = g_preferences.getFloat("gyrLpfA", config.gyroLpfAlpha);
        config.accelTrustMinG = g_preferences.getFloat("accTrMin", config.accelTrustMinG);
        config.accelTrustMaxG = g_preferences.getFloat("accTrMax", config.accelTrustMaxG);
        config.accelTrustFadeSpanG = g_preferences.getFloat("accTrFade", config.accelTrustFadeSpanG);
        config.complementaryEnabled = g_preferences.getBool("compEn", config.complementaryEnabled);
        config.accelLpfEnabled = g_preferences.getBool("accLpfEn", config.accelLpfEnabled);
        config.gyroLpfEnabled = g_preferences.getBool("gyrLpfEn", config.gyroLpfEnabled);
        config.adaptiveAccelTrustEnabled = g_preferences.getBool("accTrEn", config.adaptiveAccelTrustEnabled);
        config.debugSerial = g_preferences.getBool("debugSer", config.debugSerial);
        config.telemetryPeriodMs = g_preferences.getUInt("telMs", config.telemetryPeriodMs);

        AxisMapConfig axisMap = config.axisMap;
        axisMap.bodyLateralAxis = static_cast<AxisRef>(g_preferences.getInt("mapLat", static_cast<int32_t>(axisMap.bodyLateralAxis)));
        axisMap.bodyLongitudinalAxis = static_cast<AxisRef>(g_preferences.getInt("mapLong", static_cast<int32_t>(axisMap.bodyLongitudinalAxis)));
        axisMap.bodyVerticalAxis = static_cast<AxisRef>(g_preferences.getInt("mapVert", static_cast<int32_t>(axisMap.bodyVerticalAxis)));
        if (AxisMap::isAxisMapValid(axisMap))
        {
            config.axisMap = axisMap;
        }
    }

    // Salvataggio atomico dei parametri utente esposti all'app.
    void saveConfig(const AppConfig& config)
    {
        g_preferences.putFloat("compAlpha", config.complementaryAlpha);
        g_preferences.putFloat("accLpfA", config.accelLpfAlpha);
        g_preferences.putFloat("gyrLpfA", config.gyroLpfAlpha);
        g_preferences.putFloat("accTrMin", config.accelTrustMinG);
        g_preferences.putFloat("accTrMax", config.accelTrustMaxG);
        g_preferences.putFloat("accTrFade", config.accelTrustFadeSpanG);
        g_preferences.putBool("compEn", config.complementaryEnabled);
        g_preferences.putBool("accLpfEn", config.accelLpfEnabled);
        g_preferences.putBool("gyrLpfEn", config.gyroLpfEnabled);
        g_preferences.putBool("accTrEn", config.adaptiveAccelTrustEnabled);
        g_preferences.putBool("debugSer", config.debugSerial);
        g_preferences.putUInt("telMs", config.telemetryPeriodMs);
        g_preferences.putInt("mapLat", static_cast<int32_t>(config.axisMap.bodyLateralAxis));
        g_preferences.putInt("mapLong", static_cast<int32_t>(config.axisMap.bodyLongitudinalAxis));
        g_preferences.putInt("mapVert", static_cast<int32_t>(config.axisMap.bodyVerticalAxis));
        g_preferences.remove("peakLpfA");
        g_preferences.remove("peakEn");
        g_preferences.remove("dbgFakeTel");
    }

    // Stampa il contenuto della configurazione su seriale per diagnostica locale.
    void printConfig(const AppConfig& config)
    {
        TECHNICAL_SERIAL.println("[CFG] complementaryAlpha=" + String(config.complementaryAlpha, 3));
        TECHNICAL_SERIAL.println("[CFG] complementaryEnabled=" + String(config.complementaryEnabled));
        TECHNICAL_SERIAL.println("[CFG] accelLpfEnabled=" + String(config.accelLpfEnabled));
        TECHNICAL_SERIAL.println("[CFG] gyroLpfEnabled=" + String(config.gyroLpfEnabled));
        TECHNICAL_SERIAL.println("[CFG] adaptiveAccelTrustEnabled=" + String(config.adaptiveAccelTrustEnabled));
        TECHNICAL_SERIAL.println("[CFG] accelLpfAlpha=" + String(config.accelLpfAlpha, 3));
        TECHNICAL_SERIAL.println("[CFG] gyroLpfAlpha=" + String(config.gyroLpfAlpha, 3));
        TECHNICAL_SERIAL.println("[CFG] accelTrustMinG=" + String(config.accelTrustMinG, 3));
        TECHNICAL_SERIAL.println("[CFG] accelTrustMaxG=" + String(config.accelTrustMaxG, 3));
        TECHNICAL_SERIAL.println("[CFG] accelTrustFadeSpanG=" + String(config.accelTrustFadeSpanG, 3));
        TECHNICAL_SERIAL.println("[CFG] bodyLateralAxis=" + String(AxisMap::toString(config.axisMap.bodyLateralAxis)));
        TECHNICAL_SERIAL.println("[CFG] bodyLongitudinalAxis=" + String(AxisMap::toString(config.axisMap.bodyLongitudinalAxis)));
        TECHNICAL_SERIAL.println("[CFG] bodyVerticalAxis=" + String(AxisMap::toString(config.axisMap.bodyVerticalAxis)));
        TECHNICAL_SERIAL.println("[CFG] debugSerial=" + String(config.debugSerial));
        TECHNICAL_SERIAL.println("[CFG] telemetryPeriodMs=" + String(config.telemetryPeriodMs));
    }

    // Caricamento offset sensore: questi valori correggono il chip prima del filtro.
    void loadCalibration(CalibrationData& calibration)
    {
        calibration.accelOffsetX = g_preferences.getFloat("accOffX", 0.0f);
        calibration.accelOffsetY = g_preferences.getFloat("accOffY", 0.0f);
        calibration.accelOffsetZ = g_preferences.getFloat("accOffZ", 0.0f);
        calibration.gyroOffsetXDegS = g_preferences.getFloat("gyrOffX", 0.0f);
        calibration.gyroOffsetYDegS = g_preferences.getFloat("gyrOffY", 0.0f);
        calibration.gyroOffsetZDegS = g_preferences.getFloat("gyrOffZ", 0.0f);
        calibration.valid = g_preferences.getBool("calValid", false);
    }

    // Persistenza degli offset sensore correnti.
    void saveCalibration(const CalibrationData& calibration)
    {
        g_preferences.putFloat("accOffX", calibration.accelOffsetX);
        g_preferences.putFloat("accOffY", calibration.accelOffsetY);
        g_preferences.putFloat("accOffZ", calibration.accelOffsetZ);
        g_preferences.putFloat("gyrOffX", calibration.gyroOffsetXDegS);
        g_preferences.putFloat("gyrOffY", calibration.gyroOffsetYDegS);
        g_preferences.putFloat("gyrOffZ", calibration.gyroOffsetZDegS);
        g_preferences.putBool("calValid", calibration.valid);
    }

    // Elimina da NVS tutte le chiavi relative alla calibrazione.
    void clearCalibration()
    {
        g_preferences.remove("accOffX");
        g_preferences.remove("accOffY");
        g_preferences.remove("accOffZ");
        g_preferences.remove("gyrOffX");
        g_preferences.remove("gyrOffY");
        g_preferences.remove("gyrOffZ");
        g_preferences.remove("calValid");
    }

    // Stampa la calibrazione attuale su seriale per verifica tecnica.
    void printCalibration(const CalibrationData& calibration)
    {
        TECHNICAL_SERIAL.println("[CAL] valid=" + String(calibration.valid));
        TECHNICAL_SERIAL.println("[CAL] accelOffsetX=" + String(calibration.accelOffsetX, 6));
        TECHNICAL_SERIAL.println("[CAL] accelOffsetY=" + String(calibration.accelOffsetY, 6));
        TECHNICAL_SERIAL.println("[CAL] accelOffsetZ=" + String(calibration.accelOffsetZ, 6));
        TECHNICAL_SERIAL.println("[CAL] gyroOffsetXDegS=" + String(calibration.gyroOffsetXDegS, 6));
        TECHNICAL_SERIAL.println("[CAL] gyroOffsetYDegS=" + String(calibration.gyroOffsetYDegS, 6));
        TECHNICAL_SERIAL.println("[CAL] gyroOffsetZDegS=" + String(calibration.gyroOffsetZDegS, 6));
    }

    // Caricamento dello zero di riferimento definito dall utente sul veicolo montato.
    void loadReference(ReferenceData& reference)
    {
        reference.rollZeroDeg = g_preferences.getFloat("rollZero", 0.0f);
        reference.pitchZeroDeg = g_preferences.getFloat("pitchZero", 0.0f);
        reference.valid = g_preferences.getBool("refValid", false);
    }

    // Persistenza dello zero di riferimento utente.
    void saveReference(const ReferenceData& reference)
    {
        g_preferences.putFloat("rollZero", reference.rollZeroDeg);
        g_preferences.putFloat("pitchZero", reference.pitchZeroDeg);
        g_preferences.putBool("refValid", reference.valid);
    }

    // Elimina da NVS tutte le chiavi relative al riferimento zero.
    void clearReference()
    {
        g_preferences.remove("rollZero");
        g_preferences.remove("pitchZero");
        g_preferences.remove("refValid");
    }

    // Stampa il riferimento zero attuale su seriale per diagnostica locale.
    void printReference(const ReferenceData& reference)
    {
        TECHNICAL_SERIAL.println("[REF] valid=" + String(reference.valid));
        TECHNICAL_SERIAL.println("[REF] rollZeroDeg=" + String(reference.rollZeroDeg, 3));
        TECHNICAL_SERIAL.println("[REF] pitchZeroDeg=" + String(reference.pitchZeroDeg, 3));
    }
}
