// Strutture dati condivise tra i moduli del firmware.
// Qui vengono definite configurazione persistente, dati IMU, stato filtri e stato runtime condiviso.

#pragma once

#include <Arduino.h>

enum class AxisRef : int8_t
{
    PosX = 1,
    NegX = -1,
    PosY = 2,
    NegY = -2,
    PosZ = 3,
    NegZ = -3
};

struct AxisMapConfig
{
    // Rimappa il frame fisico del sensore nel frame canonico del veicolo:
    // X laterale, Y longitudinale, Z verticale.
    AxisRef bodyLateralAxis = AxisRef::PosX;
    AxisRef bodyLongitudinalAxis = AxisRef::PosY;
    AxisRef bodyVerticalAxis = AxisRef::PosZ;
};

struct AppConfig
{
    // Parametri dei filtri e della fusione sensori.
    float complementaryAlpha = 0.98f;
    float accelLpfAlpha = 0.15f;
    float gyroLpfAlpha = 0.20f;
    float accelTrustMinG = 0.90f;
    float accelTrustMaxG = 1.10f;
    float accelTrustFadeSpanG = 0.25f;
    // Flag di abilitazione delle singole parti della pipeline.
    bool complementaryEnabled = true;
    bool accelLpfEnabled = true;
    bool gyroLpfEnabled = true;
    bool adaptiveAccelTrustEnabled = true;
    // Mappa persistente del frame sensore verso il frame canonico del veicolo.
    AxisMapConfig axisMap;
    // Log tecnico locale su seriale.
    bool debugSerial = true;
    // Cadenza della telemetria push, sempre in formato compact.
    uint32_t telemetryPeriodMs = 100;
};

struct CalibrationData
{
    // Offset raw del sensore, applicati prima dei filtri.
    float accelOffsetX = 0.0f;
    float accelOffsetY = 0.0f;
    float accelOffsetZ = 0.0f;
    float gyroOffsetXDegS = 0.0f;
    float gyroOffsetYDegS = 0.0f;
    float gyroOffsetZDegS = 0.0f;
    bool valid = false;
};

struct ReferenceData
{
    // Zero meccanico impostato dall'utente sul veicolo montato.
    float rollZeroDeg = 0.0f;
    float pitchZeroDeg = 0.0f;
    bool valid = false;
};

struct RawImuData
{
    // Dati gia convertiti in unita fisiche, ma non ancora filtrati.
    // Il timestamp sensore descrive il campione acquisito e non cambia con il remap assi.
    float accelX = 0.0f;
    float accelY = 0.0f;
    float accelZ = 1.0f;
    float gyroXDegS = 0.0f;
    float gyroYDegS = 0.0f;
    float gyroZDegS = 0.0f;
    float temperatureC = 0.0f;
    uint32_t sensorTimestampTicks = 0;
    float sensorTimestampTickSeconds = 0.000025f;
    bool sensorTimestampValid = false;
};

struct GpsFixData
{
    // Ultimo frame UBX-NAV-PVT decodificato dal ricevitore GNSS.
    // I campi restano in unita native semplici per evitare conversioni ripetute.
    uint32_t iTowMs = 0;
    uint32_t receivedAtMs = 0;
    int32_t longitudeDegE7 = 0;
    int32_t latitudeDegE7 = 0;
    int32_t heightMslMm = 0;
    uint32_t horizontalAccMm = 0;
    int32_t velNorthMmS = 0;
    int32_t velEastMmS = 0;
    int32_t velDownMmS = 0;
    int32_t groundSpeedMmS = 0;
    int32_t headingMotionDegE5 = 0;
    uint32_t speedAccMmS = 0;
    uint32_t headingAccDegE5 = 0;
    uint8_t fixType = 0;
    uint8_t numSv = 0;
    bool validDate = false;
    bool validTime = false;
    bool gnssFixOk = false;
    bool invalidLlh = false;
    bool valid = false;
};

struct GpsAidData
{
    // Dati GPS validati e pronti per l'uso nella pipeline filtri.
    // Separati da GpsFixData per minimizzare il dato trasmesso tra task via mutex.
    // valid è false quando il GPS non è connesso, non ha fix, o il fix è scaduto:
    // in quel caso speedMs viene ignorato e la pipeline funziona come senza GPS.
    float speedMs = 0.0f;
    bool valid = false;
};

struct FilterState
{
    // Stato interno del motore di fusione: non va esposto al protocollo.
    float rollDeg = 0.0f;
    float pitchDeg = 0.0f;
    float accelLPFX = 0.0f;
    float accelLPFY = 0.0f;
    float accelLPFZ = 1.0f;
    float gyroLPFXDegS = 0.0f;
    float gyroLPFYDegS = 0.0f;
    float gyroLPFZDegS = 0.0f;
    float gyroRuntimeBiasXDegS = 0.0f;
    float gyroRuntimeBiasYDegS = 0.0f;
    float gyroRuntimeBiasZDegS = 0.0f;
    uint32_t lastSensorTimestampTicks = 0;
    uint32_t lastUpdateMs = 0;
    bool lastSensorTimestampValid = false;
    bool initialized = false;
};

struct RuntimeState
{
    // Fotografia runtime esportabile verso l'app tramite protocollo BLE.
    uint32_t timestampMs = 0;
    uint32_t imuTimestampTicks = 0;
    float rollDeg = 0.0f;
    float pitchDeg = 0.0f;
    float rawRollDeg = 0.0f;
    float rawPitchDeg = 0.0f;
    float longitudinalAccelG = 0.0f;
    float imuTemperatureC = 0.0f;
    float sampleRateHz = 0.0f;
    bool imuReady = false;
};
