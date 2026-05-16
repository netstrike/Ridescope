// Interfaccia del modulo di comunicazione.
// Il firmware espone:
// - BLE GATT per stato/configurazione, telemetria live e OTA
// La seriale non fa parte del protocollo applicativo e resta riservata ai log tecnici.

#pragma once

#include "types.h"

namespace Comm
{
    // Inizializza il sottosistema di comunicazione e avvia il trasporto BLE.
    // Lo schema del protocollo live e centralizzato nel serializer.
    void begin();
    // Fa avanzare il trasporto BLE applicando eventuali modifiche allo stato condiviso
    // entro i vincoli del protocollo attuale.
    void handleIncoming(AppConfig& config, CalibrationData& calibration, ReferenceData& reference, const RuntimeState& runtimeState);
    // Invia lo snapshot runtime corrente al client BLE attivo, se presente.
    // Il payload live compact espone i valori istantanei consentiti dal protocollo,
    // inclusi timestamp e temperatura della IMU.
    // Quando il parser NAV-PVT e attivo, il live include anche i campi GPS compact dichiarati dal protocollo.
    // Durante OTA il layer trasporto puo sospendere temporaneamente le notify live.
    void sendRuntimeState(const RuntimeState& runtimeState);
}
