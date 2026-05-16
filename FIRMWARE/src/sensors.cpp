// Gestione sensore LSM6DSOX via I2C.
// Questo file si occupa di:
// - rilevare l'indirizzo I2C del sensore
// - configurare accelerometro e giroscopio
// - leggere i registri raw
// - convertire i dati in g, deg/s e gradi Celsius
// - applicare gli offset di calibrazione

#include "sensors.h"
#include "config.h"
#include "debug.h"
#include "messages.h"

#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <Wire.h>
#include <math.h>

namespace
{
    constexpr uint8_t REG_WHO_AM_I = 0x0F;
    constexpr uint8_t REG_INT1_CTRL = 0x0D;
    constexpr uint8_t REG_CTRL1_XL = 0x10;
    constexpr uint8_t REG_CTRL2_G = 0x11;
    constexpr uint8_t REG_CTRL3_C = 0x12;
    constexpr uint8_t REG_CTRL10_C = 0x19;
    constexpr uint8_t REG_OUT_TEMP_L = 0x20;
    constexpr uint8_t REG_STATUS = 0x1E;
    constexpr uint8_t REG_OUTX_L_G = 0x22;
    constexpr uint8_t REG_OUTX_L_A = 0x28;
    constexpr uint8_t REG_TIMESTAMP0 = 0x40;
    constexpr uint8_t REG_TIMESTAMP2 = 0x42;

    constexpr float ACC_SENSITIVITY_G_PER_LSB = 0.000061f;
    constexpr float GYRO_SENSITIVITY_DPS_PER_LSB = 0.00875f;
    constexpr float TEMP_SENSITIVITY_LSB_PER_C = 256.0f;
    constexpr float TEMP_OFFSET_C = 25.0f;
    constexpr float SENSOR_TIMESTAMP_TICK_S = 0.000025f;
    constexpr float GRAVITY_TARGET_G = 1.0f;
    constexpr uint32_t SENSOR_HEALTH_PROBE_MS = 250;
    constexpr uint32_t SENSOR_RECOVERY_RETRY_MS = 1000;
    constexpr uint8_t CTRL10_C_TIMESTAMP_EN = 0x20;
    constexpr uint8_t TIMESTAMP_RESET_VALUE = 0xAA;

    struct CalibrationJob
    {
        bool running = false;
        bool resultReady = false;
        bool resultValid = false;
        uint16_t targetSamples = 0;
        uint16_t collectedSamples = 0;
        uint16_t delayMs = 0;
        uint32_t nextSampleAtMs = 0;
        float sumAx = 0.0f;
        float sumAy = 0.0f;
        float sumAz = 0.0f;
        float sumGx = 0.0f;
        float sumGy = 0.0f;
        float sumGz = 0.0f;
        CalibrationData result;
    };

    bool g_sensorReady = false;
    uint8_t g_sensorAddress = 0;
    CalibrationData g_calibration;
    portMUX_TYPE g_calibrationMux = portMUX_INITIALIZER_UNLOCKED;
    volatile bool g_dataReadyInterruptSeen = false;
    bool g_int1Enabled = false;
    bool g_int1Attached = false;
    CalibrationJob g_calibrationJob;
    uint32_t g_lastSuccessfulCommMs = 0;
    uint32_t g_lastRecoveryAttemptMs = 0;

    bool configureTimestamping();

    // ISR minimale che memorizza il verificarsi di un nuovo campione pronto.
    void IRAM_ATTR onDataReadyInterrupt()
    {
        g_dataReadyInterruptSeen = true;
    }

    // Memorizza l'istante dell'ultima transazione I2C andata a buon fine.
    void noteSuccessfulCommunication()
    {
        g_lastSuccessfulCommMs = millis();
    }

    // Interrompe una calibrazione in corso se la IMU sparisce a runtime.
    void abortCalibrationJob()
    {
        if (!g_calibrationJob.running)
        {
            return;
        }

        g_calibrationJob.running = false;
        g_calibrationJob.resultReady = true;
        g_calibrationJob.resultValid = false;
        g_calibrationJob.result = {};
    }

    // Marca la IMU come non disponibile e pianifica un recovery automatico.
    void markSensorUnavailable(bool logRuntimeFault)
    {
        const bool wasReady = g_sensorReady;
        g_sensorReady = false;
        g_sensorAddress = 0;
        g_dataReadyInterruptSeen = false;
        g_lastSuccessfulCommMs = 0;
        g_lastRecoveryAttemptMs = millis();
        abortCalibrationJob();

        if (logRuntimeFault && wasReady)
        {
            Debug::info(Messages::Log::kImuRuntimeDisconnected);
        }
    }

    // Scrive un registro del sensore gia indirizzato su I2C.
    bool writeRegister(uint8_t reg, uint8_t value)
    {
        // Scrittura semplice a singolo registro sul device gia selezionato.
        Wire.beginTransmission(g_sensorAddress);
        Wire.write(reg);
        Wire.write(value);
        const bool ok = (Wire.endTransmission() == 0);
        if (ok)
        {
            noteSuccessfulCommunication();
        }
        else if (g_sensorReady)
        {
            markSensorUnavailable(true);
        }
        return ok;
    }

    // Legge una sequenza di registri contigui dal sensore.
    bool readRegisters(uint8_t startReg, uint8_t* buffer, size_t len)
    {
        Wire.beginTransmission(g_sensorAddress);
        Wire.write(startReg);
        if (Wire.endTransmission(false) != 0)
        {
            if (g_sensorReady)
            {
                markSensorUnavailable(true);
            }
            return false;
        }

        const size_t readCount = Wire.requestFrom((int)g_sensorAddress, (int)len);
        if (readCount != len)
        {
            if (g_sensorReady)
            {
                markSensorUnavailable(true);
            }
            return false;
        }

        for (size_t i = 0; i < len; ++i)
        {
            if (!Wire.available())
            {
                if (g_sensorReady)
                {
                    markSensorUnavailable(true);
                }
                return false;
            }
            buffer[i] = (uint8_t)Wire.read();
        }

        noteSuccessfulCommunication();
        return true;
    }

    // Legge il registro STATUS del sensore.
    bool readStatus(uint8_t& statusValue)
    {
        return readRegisters(REG_STATUS, &statusValue, 1);
    }

    // Verifica se all'indirizzo candidato risponde effettivamente un LSM6DSOX.
    bool detectAddress(uint8_t candidateAddress)
    {
        uint8_t whoAmI = 0;
        g_sensorAddress = candidateAddress;
        if (!readRegisters(REG_WHO_AM_I, &whoAmI, 1))
        {
            return false;
        }
        return whoAmI == LSM6DSOX_WHO_AM_I;
    }

    // Prova a rilevare automaticamente l'indirizzo valido del sensore.
    bool detectConfiguredAddress(bool emitDiscoveryLog)
    {
        if (detectAddress(LSM6DSOX_I2C_ADDR_LOW))
        {
            if (emitDiscoveryLog)
            {
                Debug::info(Messages::Log::kImuFound6A);
            }
            return true;
        }

        if (detectAddress(LSM6DSOX_I2C_ADDR_HIGH))
        {
            if (emitDiscoveryLog)
            {
                Debug::info(Messages::Log::kImuFound6B);
            }
            return true;
        }

        g_sensorAddress = 0;
        return false;
    }

    // Configura l'eventuale interrupt INT1 una sola volta lato MCU
    // e ad ogni init lato sensore.
    bool ensureInt1Configured()
    {
        g_int1Enabled = (LSM6DSOX_INT1_PIN >= 0);
        if (!g_int1Enabled)
        {
            return true;
        }

        if (!g_int1Attached)
        {
            pinMode(LSM6DSOX_INT1_PIN, INPUT);
            attachInterrupt(digitalPinToInterrupt(LSM6DSOX_INT1_PIN), onDataReadyInterrupt, RISING);
            g_int1Attached = true;
        }

        return writeRegister(REG_INT1_CTRL, 0x03);
    }

    // Esegue una init completa del sensore, riusabile sia al boot sia nel recovery runtime.
    bool tryInitializeSensor(bool emitDiscoveryLog, bool emitFailureLog)
    {
        Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN, I2C_CLOCK_HZ);
        delay(20);

        if (!detectConfiguredAddress(emitDiscoveryLog))
        {
            markSensorUnavailable(false);
            if (emitFailureLog)
            {
                Debug::info(Messages::Log::kImuNotFound);
            }
            return false;
        }

        if (!writeRegister(REG_CTRL3_C, 0x44))
        {
            markSensorUnavailable(false);
            if (emitFailureLog)
            {
                Debug::info(Messages::Log::kCtrl3WriteFailed);
            }
            return false;
        }

        if (!writeRegister(REG_CTRL1_XL, 0x40))
        {
            markSensorUnavailable(false);
            if (emitFailureLog)
            {
                Debug::info(Messages::Log::kCtrl1WriteFailed);
            }
            return false;
        }

        if (!writeRegister(REG_CTRL2_G, 0x40))
        {
            markSensorUnavailable(false);
            if (emitFailureLog)
            {
                Debug::info(Messages::Log::kCtrl2WriteFailed);
            }
            return false;
        }

        if (!configureTimestamping())
        {
            markSensorUnavailable(false);
            if (emitFailureLog)
            {
                Debug::info(Messages::Log::kTimestampInitFailed);
            }
            return false;
        }

        if (!ensureInt1Configured())
        {
            markSensorUnavailable(false);
            if (emitFailureLog)
            {
                Debug::info(Messages::Log::kInt1WriteFailed);
            }
            return false;
        }

        delay(50);

        g_sensorReady = true;
        g_dataReadyInterruptSeen = false;
        noteSuccessfulCommunication();
        return true;
    }

    // Verifica a bassa frequenza che la IMU continui a rispondere anche se non arrivano campioni.
    void probeSensorHealthIfNeeded(uint32_t nowMs)
    {
        if (!g_sensorReady)
        {
            return;
        }

        if (g_lastSuccessfulCommMs != 0 && (nowMs - g_lastSuccessfulCommMs) < SENSOR_HEALTH_PROBE_MS)
        {
            return;
        }

        uint8_t whoAmI = 0;
        if (!readRegisters(REG_WHO_AM_I, &whoAmI, 1))
        {
            return;
        }

        if (whoAmI != LSM6DSOX_WHO_AM_I)
        {
            markSensorUnavailable(true);
        }
    }

    // Tenta periodicamente una reinizializzazione completa quando la IMU non e pronta.
    void attemptAutomaticRecovery(uint32_t nowMs)
    {
        if (g_sensorReady)
        {
            return;
        }

        if ((nowMs - g_lastRecoveryAttemptMs) < SENSOR_RECOVERY_RETRY_MS)
        {
            return;
        }

        g_lastRecoveryAttemptMs = nowMs;
        if (tryInitializeSensor(false, false))
        {
            Debug::info(Messages::Log::kImuRuntimeRecovered);
        }
    }

    // Ricompone un int16 little-endian a partire da due byte letti dal sensore.
    int16_t makeInt16(uint8_t lowByte, uint8_t highByte)
    {
        return (int16_t)((uint16_t)highByte << 8U | (uint16_t)lowByte);
    }

    // Ricompone il timestamp hardware little-endian del sensore.
    uint32_t makeUInt32(uint8_t byte0, uint8_t byte1, uint8_t byte2, uint8_t byte3)
    {
        return (static_cast<uint32_t>(byte3) << 24U) |
               (static_cast<uint32_t>(byte2) << 16U) |
               (static_cast<uint32_t>(byte1) << 8U) |
               static_cast<uint32_t>(byte0);
    }

    // Abilita il timestamp interno del LSM6DSOX e riallinea il contatore a zero.
    bool configureTimestamping()
    {
        uint8_t ctrl10Value = 0;
        if (!readRegisters(REG_CTRL10_C, &ctrl10Value, 1))
        {
            return false;
        }

        ctrl10Value |= CTRL10_C_TIMESTAMP_EN;
        if (!writeRegister(REG_CTRL10_C, ctrl10Value))
        {
            return false;
        }

        return writeRegister(REG_TIMESTAMP2, TIMESTAMP_RESET_VALUE);
    }

    // Legge temperatura, accelerometro e giroscopio raw e li converte in unita fisiche.
    bool readImuRawNoCalibration(RawImuData& imuData)
    {
        // Questa funzione legge il chip "nudo": eventuali offset vengono applicati dopo.
        if (!g_sensorReady)
        {
            imuData = {};
            imuData.accelZ = 1.0f;
            return false;
        }

        uint8_t tempRaw[2] = {0};
        uint8_t gyroRaw[6] = {0};
        uint8_t accelRaw[6] = {0};
        uint8_t timestampRaw[4] = {0};

        if (!readRegisters(REG_OUT_TEMP_L, tempRaw, sizeof(tempRaw)) ||
            !readRegisters(REG_OUTX_L_G, gyroRaw, sizeof(gyroRaw)) ||
            !readRegisters(REG_OUTX_L_A, accelRaw, sizeof(accelRaw)) ||
            !readRegisters(REG_TIMESTAMP0, timestampRaw, sizeof(timestampRaw)))
        {
            imuData = {};
            imuData.accelZ = 1.0f;
            return false;
        }

        const int16_t temp = makeInt16(tempRaw[0], tempRaw[1]);
        const int16_t gx = makeInt16(gyroRaw[0], gyroRaw[1]);
        const int16_t gy = makeInt16(gyroRaw[2], gyroRaw[3]);
        const int16_t gz = makeInt16(gyroRaw[4], gyroRaw[5]);

        const int16_t ax = makeInt16(accelRaw[0], accelRaw[1]);
        const int16_t ay = makeInt16(accelRaw[2], accelRaw[3]);
        const int16_t az = makeInt16(accelRaw[4], accelRaw[5]);

        imuData.accelX = ax * ACC_SENSITIVITY_G_PER_LSB;
        imuData.accelY = ay * ACC_SENSITIVITY_G_PER_LSB;
        imuData.accelZ = az * ACC_SENSITIVITY_G_PER_LSB;
        imuData.gyroXDegS = gx * GYRO_SENSITIVITY_DPS_PER_LSB;
        imuData.gyroYDegS = gy * GYRO_SENSITIVITY_DPS_PER_LSB;
        imuData.gyroZDegS = gz * GYRO_SENSITIVITY_DPS_PER_LSB;
        imuData.temperatureC = TEMP_OFFSET_C + (static_cast<float>(temp) / TEMP_SENSITIVITY_LSB_PER_C);
        imuData.sensorTimestampTicks = makeUInt32(timestampRaw[0], timestampRaw[1], timestampRaw[2], timestampRaw[3]);
        imuData.sensorTimestampTickSeconds = SENSOR_TIMESTAMP_TICK_S;
        imuData.sensorTimestampValid = true;
        return true;
    }

    // Calcola gli offset medi finali a partire dai campioni raccolti in quiete.
    bool finalizeCalibration(const CalibrationJob& job, CalibrationData& calibrationOut)
    {
        // La calibrazione da fermo stima il bias medio del gyro e
        // riallinea il modulo dell'accelerazione a 1 g.
        if (job.collectedSamples == 0)
        {
            return false;
        }

        const float sampleCount = static_cast<float>(job.collectedSamples);
        const float avgAx = job.sumAx / sampleCount;
        const float avgAy = job.sumAy / sampleCount;
        const float avgAz = job.sumAz / sampleCount;
        const float avgGx = job.sumGx / sampleCount;
        const float avgGy = job.sumGy / sampleCount;
        const float avgGz = job.sumGz / sampleCount;

        const float accelNorm = sqrtf(avgAx * avgAx + avgAy * avgAy + avgAz * avgAz);
        if (accelNorm < 0.2f)
        {
            return false;
        }

        const float expectedAx = avgAx / accelNorm * GRAVITY_TARGET_G;
        const float expectedAy = avgAy / accelNorm * GRAVITY_TARGET_G;
        const float expectedAz = avgAz / accelNorm * GRAVITY_TARGET_G;

        calibrationOut.accelOffsetX = avgAx - expectedAx;
        calibrationOut.accelOffsetY = avgAy - expectedAy;
        calibrationOut.accelOffsetZ = avgAz - expectedAz;
        calibrationOut.gyroOffsetXDegS = avgGx;
        calibrationOut.gyroOffsetYDegS = avgGy;
        calibrationOut.gyroOffsetZDegS = avgGz;
        calibrationOut.valid = true;
        return true;
    }
}

namespace Sensors
{
    // Inizializza il sensore, configura i registri base e l'eventuale INT1.
    bool begin()
    {
        return tryInitializeSensor(true, true);
    }

    // Aggiorna in sezione critica la calibrazione usata per correggere i campioni.
    void setCalibration(const CalibrationData& calibration)
    {
        portENTER_CRITICAL(&g_calibrationMux);
        g_calibration = calibration;
        portEXIT_CRITICAL(&g_calibrationMux);
    }

    // Legge in sezione critica la calibrazione attualmente in uso.
    void getCalibration(CalibrationData& calibration)
    {
        portENTER_CRITICAL(&g_calibrationMux);
        calibration = g_calibration;
        portEXIT_CRITICAL(&g_calibrationMux);
    }

    // Prepara una nuova procedura di calibrazione cooperativa.
    bool startCalibrationStill(uint16_t samples, uint16_t delayMs)
    {
        // E ammessa una sola calibrazione asincrona alla volta.
        if (!g_sensorReady || g_calibrationJob.running || samples == 0)
        {
            return false;
        }

        g_calibrationJob = {};
        g_calibrationJob.running = true;
        g_calibrationJob.targetSamples = samples;
        g_calibrationJob.delayMs = delayMs;
        g_calibrationJob.nextSampleAtMs = millis();
        return true;
    }

    // Esegue un passo della calibrazione asincrona se i tempi lo consentono.
    void update()
    {
        const uint32_t now = millis();
        if (g_sensorReady)
        {
            probeSensorHealthIfNeeded(now);
        }
        else
        {
            attemptAutomaticRecovery(now);
        }

        // La calibrazione viene portata avanti cooperativamente dal task sensore,
        // senza bloccare il loop principale o introdurre attese lunghe.
        if (!g_calibrationJob.running)
        {
            return;
        }

        if (now < g_calibrationJob.nextSampleAtMs)
        {
            return;
        }

        RawImuData sample;
        if (!readImuRawNoCalibration(sample))
        {
            g_calibrationJob.running = false;
            g_calibrationJob.resultReady = true;
            g_calibrationJob.resultValid = false;
            return;
        }

        g_calibrationJob.sumAx += sample.accelX;
        g_calibrationJob.sumAy += sample.accelY;
        g_calibrationJob.sumAz += sample.accelZ;
        g_calibrationJob.sumGx += sample.gyroXDegS;
        g_calibrationJob.sumGy += sample.gyroYDegS;
        g_calibrationJob.sumGz += sample.gyroZDegS;
        ++g_calibrationJob.collectedSamples;
        g_calibrationJob.nextSampleAtMs = now + g_calibrationJob.delayMs;

        if (g_calibrationJob.collectedSamples >= g_calibrationJob.targetSamples)
        {
            g_calibrationJob.running = false;
            g_calibrationJob.resultValid = finalizeCalibration(g_calibrationJob, g_calibrationJob.result);
            g_calibrationJob.resultReady = true;
        }
    }

    // Indica se il job di calibrazione e ancora in esecuzione.
    bool isCalibrationRunning()
    {
        return g_calibrationJob.running;
    }

    // Converte lo stato del job di calibrazione in percentuale.
    uint8_t calibrationProgressPercent()
    {
        if (g_calibrationJob.running && g_calibrationJob.targetSamples > 0)
        {
            return static_cast<uint8_t>((static_cast<uint32_t>(g_calibrationJob.collectedSamples) * 100U) / g_calibrationJob.targetSamples);
        }
        if (g_calibrationJob.resultReady)
        {
            return 100;
        }
        return 0;
    }

    // Restituisce una sola volta il risultato finale della calibrazione.
    bool takeCalibrationResult(CalibrationData& calibrationOut)
    {
        if (!g_calibrationJob.resultReady)
        {
            return false;
        }

        const bool ok = g_calibrationJob.resultValid;
        if (ok)
        {
            calibrationOut = g_calibrationJob.result;
        }

        g_calibrationJob.resultReady = false;
        g_calibrationJob.resultValid = false;
        g_calibrationJob.result = {};
        return ok;
    }

    // Legge un campione IMU e applica l'eventuale calibrazione attiva.
    bool readImu(RawImuData& imuData)
    {
        if (!readImuRawNoCalibration(imuData))
        {
            return false;
        }

        CalibrationData calibrationSnapshot;
        getCalibration(calibrationSnapshot);

        if (calibrationSnapshot.valid)
        {
            // Gli offset salvati in NVS correggono il dato prima della fusione.
            imuData.accelX -= calibrationSnapshot.accelOffsetX;
            imuData.accelY -= calibrationSnapshot.accelOffsetY;
            imuData.accelZ -= calibrationSnapshot.accelOffsetZ;
            imuData.gyroXDegS -= calibrationSnapshot.gyroOffsetXDegS;
            imuData.gyroYDegS -= calibrationSnapshot.gyroOffsetYDegS;
            imuData.gyroZDegS -= calibrationSnapshot.gyroOffsetZDegS;
        }

        return true;
    }

    // Restituisce se il sensore e operativo.
    bool isReady()
    {
        return g_sensorReady;
    }

    // Restituisce l'indirizzo I2C attualmente in uso.
    uint8_t getAddress()
    {
        return g_sensorAddress;
    }

    // Verifica se esiste un campione pronto senza consumare il latch INT1.
    bool isDataReadyPending()
    {
        if (!g_sensorReady)
        {
            return false;
        }

        if (g_int1Enabled)
        {
            // In modalita interrupt leggiamo solo il latch software senza consumarlo.
            return g_dataReadyInterruptSeen;
        }

        uint8_t statusValue = 0;
        if (!readStatus(statusValue))
        {
            return false;
        }

        return (statusValue & 0x03U) != 0;
    }

    // Verifica e consuma il latch data-ready se si usa INT1.
    bool isDataReady()
    {
        // Questa e la versione "consume": il task sensore la usa per marcare
        // come gestito il campione notificato via INT1.
        if (!isDataReadyPending())
        {
            return false;
        }

        if (g_int1Enabled)
        {
            g_dataReadyInterruptSeen = false;
        }

        return true;
    }

    // Indica se il data-ready hardware e configurato su INT1.
    bool isInt1Enabled()
    {
        return g_int1Enabled;
    }
}
