#pragma once

#include "comm_shared.h"

// Layer di trasporto concreto:
// - espone un servizio BLE GATT custom
// - gestisce pairing, telemetria notify e comandi JSON
// - coordina l'OTA via characteristic dedicata
namespace CommTransport
{
    // Inizializza il server BLE GATT, il pairing e l'advertising.
    // Non decide lo schema dei payload: quello resta nel layer protocollo.
    void begin(CommTransportRuntime& runtime);
    // Fa avanzare il trasporto BLE usando il contesto applicativo corrente.
    void handleIncoming(const CommRequestContext& context, CommTransportRuntime& runtime);
    // Invia telemetria live al client BLE autenticato e sottoscritto,
    // compresi timestamp e temperatura IMU del campione corrente.
    // Se previsto dal protocollo corrente, include anche i campi GPS compact nel payload live.
    // Durante un OTA in corso le notify live vengono sospese per lasciare banda al trasferimento.
    void sendRuntimeState(const RuntimeState& runtimeState);
}
