#pragma once

#include <Arduino.h>

#include "types.h"

// Valore sentinella usato quando nessuna connessione BLE e attualmente attiva.
static constexpr uint16_t COMM_INVALID_BLE_CONN = 0xFFFF;
static constexpr const char* COMM_PROTOCOL_VERSION = "4.11";

struct CommTransportRuntime
{
    // Stato osservabile del layer BLE/OTA.
    bool bleReady = false;
    bool bleAdvertising = false;
    bool bleClientConnected = false;
    bool bleClientAuthenticated = false;
    bool bleResponseSubscribed = false;
    bool bleLiveSubscribed = false;
    uint16_t bleConnId = COMM_INVALID_BLE_CONN;
    uint16_t bleMtu = 23;
    bool updateInProgress = false;
    bool updateSuccess = false;
    bool rebootScheduled = false;
    uint8_t progressPercent = 0;
    size_t expectedSize = 0;
    size_t writtenBytes = 0;
    String lastError;
};

struct CommRequestContext
{
    // Puntatori allo stato applicativo manipolabile dal protocollo.
    AppConfig* config = nullptr;
    CalibrationData* calibration = nullptr;
    ReferenceData* reference = nullptr;
    const RuntimeState* runtimeState = nullptr;
};
