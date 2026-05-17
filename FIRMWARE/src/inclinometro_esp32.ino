// Firmware principale dell'inclinometro moto.
// Questo file orchestra i moduli: NVS, sensore IMU, filtri e comunicazione.
//
// Flusso logico:
// 1) avvio seriale e debug
// 2) caricamento configurazione, calibrazione e riferimenti da NVS
// 3) inizializzazione del sensore LSM6DSOX e configurazione della UART GPS opzionale
// 4) avvio task dedicato ad acquisizione e filtri
// 5) loop principale riservato a comunicazione e telemetria

#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>

#include "config.h"
#include "types.h"
#include "debug.h"
#include "gps.h"
#include "storage.h"
#include "sensors.h"
#include "filters.h"
#include "comm.h"
#include "messages.h"

static AppConfig g_config;
static RuntimeState g_runtimeState;
static CalibrationData g_calibration;
static ReferenceData g_reference;
static GpsAidData g_gpsAid;

static SemaphoreHandle_t g_stateMutex = nullptr;
static uint32_t g_lastTelemetryMs = 0;

namespace
{
    // Converte l'ultimo fix GNSS in ausilio cinematico per i filtri.
    // Se il GPS non e presente, non sta streamando o il fix non e valido,
    // restituisce valid=false e la pipeline resta IMU-only.
    GpsAidData makeGpsAidFromCurrentFix()
    {
        GpsFixData gpsFix {};
        GpsAidData gpsAid {};
        const bool hasFix = Gps::readFix(gpsFix);
        if (hasFix && Gps::streaming() && gpsFix.valid && Gps::fixAgeMs() < GPS_FIX_STALE_TIMEOUT_MS)
        {
            const float groundSpeedMs = gpsFix.groundSpeedMmS > 0
                ? gpsFix.groundSpeedMmS * 0.001f
                : 0.0f;
            gpsAid.speedMs = groundSpeedMs;
            gpsAid.valid = true;
        }
        return gpsAid;
    }

    // Confronta due strutture di calibrazione per capire se il client le ha modificate.
    bool calibrationEquals(const CalibrationData& a, const CalibrationData& b)
    {
        // Il confronto serve per capire se la calibrazione e cambiata davvero
        // prima di ricopiarla nello stato condiviso del loop principale.
        return a.accelOffsetX == b.accelOffsetX &&
               a.accelOffsetY == b.accelOffsetY &&
               a.accelOffsetZ == b.accelOffsetZ &&
               a.gyroOffsetXDegS == b.gyroOffsetXDegS &&
               a.gyroOffsetYDegS == b.gyroOffsetYDegS &&
               a.gyroOffsetZDegS == b.gyroOffsetZDegS &&
               a.valid == b.valid;
    }

    // Acquisisce il mutex dello stato condiviso tra task sensore e loop principale.
    bool lockState(TickType_t timeoutTicks = portMAX_DELAY)
    {
        // Tutto lo stato condiviso tra task sensore e loop principale passa da questo mutex.
        return g_stateMutex != nullptr && xSemaphoreTake(g_stateMutex, timeoutTicks) == pdTRUE;
    }

    // Rilascia il mutex dello stato condiviso se inizializzato.
    void unlockState()
    {
        if (g_stateMutex != nullptr)
        {
            xSemaphoreGive(g_stateMutex);
        }
    }

    // Azzera solo i valori istantanei della telemetria runtime.
    void clearInstantTelemetry(RuntimeState& runtimeState)
    {
        runtimeState.imuTimestampTicks = 0;
        runtimeState.rollDeg = 0.0f;
        runtimeState.pitchDeg = 0.0f;
        runtimeState.rawRollDeg = 0.0f;
        runtimeState.rawPitchDeg = 0.0f;
        runtimeState.longitudinalAccelG = 0.0f;
        runtimeState.imuTemperatureC = 0.0f;
        runtimeState.sampleRateHz = 0.0f;
        runtimeState.imuReady = false;
    }

    // Task dedicato ad acquisizione IMU, aggiornamento filtri e stato runtime.
    void sensorTask(void* parameter)
    {
        (void)parameter;

        RawImuData rawImu {};
        FilterState filterState {};
        RuntimeState runtimeState {};
        AppConfig configSnapshot {};
        CalibrationData calibrationSnapshot {};
        ReferenceData referenceSnapshot {};
        GpsAidData gpsAidSnapshot {};
        uint32_t lastLoopMs = 0;
        bool lastPhysicalImuReady = Sensors::isReady();

        if (lockState())
        {
            // Il task sensore parte da una fotografia coerente dello stato globale.
            configSnapshot = g_config;
            calibrationSnapshot = g_calibration;
            referenceSnapshot = g_reference;
            runtimeState = g_runtimeState;
            unlockState();
        }

        Filters::begin(filterState);

        for (;;)
        {
            const uint32_t now = millis();
            if ((now - lastLoopMs) < LOOP_INTERVAL_MS)
            {
                vTaskDelay(pdMS_TO_TICKS(1));
                continue;
            }
            lastLoopMs = now;

            if (lockState(pdMS_TO_TICKS(5)))
            {
                // Le modifiche applicative vengono assorbite in blocco a inizio ciclo.
                configSnapshot = g_config;
                calibrationSnapshot = g_calibration;
                referenceSnapshot = g_reference;
                gpsAidSnapshot = g_gpsAid;
                unlockState();
            }

            runtimeState.imuReady = Sensors::isReady();
            const bool sampleReady = runtimeState.imuReady && Sensors::isDataReady();
            if (sampleReady && Sensors::readImu(rawImu))
            {
                // I filtri vengono aggiornati solo con campioni nuovi e validi.
                Filters::update(configSnapshot, rawImu, referenceSnapshot, gpsAidSnapshot, filterState, runtimeState);
            }
            else if (!runtimeState.imuReady)
            {
                clearInstantTelemetry(runtimeState);
            }

            Sensors::update();
            const bool physicalImuReady = Sensors::isReady();
            if (physicalImuReady != lastPhysicalImuReady)
            {
                // Dopo una perdita o un recovery IMU il filtro va riallineato
                // per evitare integrazione su dt spurio o stato interno stantio.
                Filters::begin(filterState);
            }
            if (physicalImuReady && !lastPhysicalImuReady)
            {
                // Se la IMU torna online dopo un fault o dopo un boot senza sensore,
                // riapplica la calibrazione gia presente nello stato condiviso/NVS.
                Sensors::setCalibration(calibrationSnapshot);
            }

            runtimeState.imuReady = physicalImuReady;
            if (!physicalImuReady)
            {
                clearInstantTelemetry(runtimeState);
            }
            lastPhysicalImuReady = physicalImuReady;

            CalibrationData completedCalibration;
            if (Sensors::takeCalibrationResult(completedCalibration))
            {
                // Quando la calibrazione asincrona termina, il risultato viene applicato subito
                // al sensore e poi copiato nello stato condiviso e in NVS.
                Sensors::setCalibration(completedCalibration);
                if (lockState(pdMS_TO_TICKS(20)))
                {
                    g_calibration = completedCalibration;
                    unlockState();
                }
                Storage::saveCalibration(completedCalibration);
            }

            runtimeState.timestampMs = now;

            if (lockState(pdMS_TO_TICKS(5)))
            {
                g_runtimeState = runtimeState;
                unlockState();
            }
        }
    }
}

// Inizializza firmware, persistenza, IMU, comunicazione e task sensore.
void setup()
{
    TECHNICAL_SERIAL.begin(SERIAL_BAUDRATE);
    delay(200);

    Debug::begin();

    g_stateMutex = xSemaphoreCreateMutex();

    Storage::begin();
    Storage::loadConfig(g_config);
    Storage::loadCalibration(g_calibration);
    Storage::loadReference(g_reference);
    Debug::setEnabled(g_config.debugSerial);
    Debug::info(Messages::Log::kBootFirmware);
    if (g_config.debugSerial)
    {
        Storage::printConfig(g_config);
        Storage::printCalibration(g_calibration);
        Storage::printReference(g_reference);
    }

    const bool sensorOk = Sensors::begin();
    if (!sensorOk)
    {
        Debug::info(Messages::Log::kImuMissing);
    }
    else
    {
        Sensors::setCalibration(g_calibration);
    }

    Gps::begin();
    Comm::begin();

    xTaskCreate(sensorTask, "sensor_task", 6144, nullptr, 2, nullptr);
}

// Esegue il polling della comunicazione e pubblica periodicamente la telemetria live.
void loop()
{
    AppConfig configSnapshot {};
    CalibrationData calibrationSnapshot {};
    CalibrationData calibrationBefore {};
    ReferenceData referenceSnapshot {};
    RuntimeState runtimeStateSnapshot {};

    // Il parser GPS viene fatto avanzare subito, cosi lo status vede dati freschi nello stesso ciclo.
    Gps::update();

    // Aggiorna i dati GPS opzionali per la pipeline filtri.
    // gpsAid.valid rimane false se il GPS non è connesso, non ha fix 3D valido o il fix è scaduto:
    // In ogni altro caso gpsAid.valid resta false e il sensor task lavora in modalita IMU-only.
    {
        const GpsAidData newGpsAid = makeGpsAidFromCurrentFix();
        if (lockState(pdMS_TO_TICKS(5)))
        {
            g_gpsAid = newGpsAid;
            unlockState();
        }
    }

    if (!lockState(pdMS_TO_TICKS(10)))
    {
        vTaskDelay(pdMS_TO_TICKS(1));
        return;
    }
    // Il loop principale usa sempre snapshot locali, cosi la costruzione delle risposte
    // non trattiene il mutex piu del necessario.
    configSnapshot = g_config;
    calibrationSnapshot = g_calibration;
    calibrationBefore = calibrationSnapshot;
    referenceSnapshot = g_reference;
    runtimeStateSnapshot = g_runtimeState;
    unlockState();

    Comm::handleIncoming(configSnapshot, calibrationSnapshot, referenceSnapshot, runtimeStateSnapshot);

    if (lockState(pdMS_TO_TICKS(10)))
    {
        g_config = configSnapshot;
        if (!calibrationEquals(calibrationBefore, calibrationSnapshot))
        {
            g_calibration = calibrationSnapshot;
        }
        g_reference = referenceSnapshot;
        unlockState();
    }

    const uint32_t now = millis();
    if ((now - g_lastTelemetryMs) >= configSnapshot.telemetryPeriodMs)
    {
        g_lastTelemetryMs = now;
        Comm::sendRuntimeState(runtimeStateSnapshot);
    }

    vTaskDelay(pdMS_TO_TICKS(1));
}
