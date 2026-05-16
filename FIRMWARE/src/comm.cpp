#include "comm.h"

#include "comm_shared.h"
#include "comm_transport.h"

namespace
{
    // Stato runtime del layer trasporto, separato dal resto del firmware
    // per evitare dipendenze dirette da BLE nel main loop.
    CommTransportRuntime g_transportRuntime;
}

namespace Comm
{
    // Avvia il layer trasporto usando la configurazione runtime corrente.
    // Le informazioni statiche e dinamiche di protocollo vengono serializzate da CommProtocol.
    void begin()
    {
        CommTransport::begin(g_transportRuntime);
    }

    // Converte lo stato firmware in un contesto mutabile per il layer trasporto.
    // I vincoli sui campi effettivamente modificabili restano centralizzati nel dispatcher.
    void handleIncoming(AppConfig& config, CalibrationData& calibration, ReferenceData& reference, const RuntimeState& runtimeState)
    {
        // Il facade costruisce un contesto leggero e lo passa al layer trasporto.
        CommRequestContext context;
        context.config = &config;
        context.calibration = &calibration;
        context.reference = &reference;
        context.runtimeState = &runtimeState;
        CommTransport::handleIncoming(context, g_transportRuntime);
    }

    // Propaga la telemetria runtime compact al trasporto senza esporre dettagli GATT.
    // Il payload resta limitato ai valori istantanei previsti dal protocollo,
    // inclusi i nuovi metadati IMU del campione corrente.
    // Se abilitato dal protocollo corrente, il live include anche il sottoinsieme GPS compact.
    // Il trasporto puo sospendere queste notify mentre un OTA e in corso.
    void sendRuntimeState(const RuntimeState& runtimeState)
    {
        CommTransport::sendRuntimeState(runtimeState);
    }
}
