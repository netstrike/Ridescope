// Interfaccia del modulo sensore.
// Questo modulo nasconde i dettagli del LSM6DSOX e fornisce dati fisici gia convertiti,
// inclusi timestamp hardware e temperatura interna.

#pragma once

#include "types.h"

namespace Sensors
{
    // Inizializza il chip IMU e verifica la presenza del dispositivo su I2C.
    // Se il dispositivo non e disponibile al boot, il modulo continuera a tentare
    // un recovery automatico anche a runtime.
    bool begin();
    // Sostituisce la calibrazione attiva applicata ai campioni raw.
    void setCalibration(const CalibrationData& calibration);
    // Restituisce una copia consistente della calibrazione attiva.
    void getCalibration(CalibrationData& calibration);
    // Avvia una calibrazione da fermo eseguita in modo cooperativo dal task sensore.
    bool startCalibrationStill(uint16_t samples = 300, uint16_t delayMs = 5);
    // Fa avanzare l'eventuale procedura di calibrazione asincrona e il monitoraggio
    // runtime della IMU, inclusi fault I2C e recovery automatico.
    void update();
    // Indica se una calibrazione da fermo e attualmente in corso.
    bool isCalibrationRunning();
    // Restituisce l'avanzamento percentuale della calibrazione corrente o appena conclusa.
    uint8_t calibrationProgressPercent();
    // Preleva il risultato della calibrazione conclusa e ne azzera il latch interno.
    bool takeCalibrationResult(CalibrationData& calibrationOut);
    // Restituisce true solo se e stato letto un campione valido.
    // Il campione include anche il timestamp hardware del sensore per la stima del dt.
    bool readImu(RawImuData& imuData);
    // Restituisce se la IMU e attualmente raggiungibile e configurata correttamente.
    bool isReady();
    // Restituisce l'indirizzo I2C effettivo della IMU rilevata.
    uint8_t getAddress();
    // Lettura passiva dello stato data-ready: non consuma il latch interrupt.
    bool isDataReadyPending();
    // Lettura usata dal task sensore: in modalita INT1 consuma il latch interrupt.
    bool isDataReady();
    // Indica se il firmware usa il pin INT1 per il data-ready.
    bool isInt1Enabled();
}
