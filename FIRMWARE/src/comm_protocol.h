#pragma once

#include "comm_shared.h"

// Helper di serializzazione del protocollo.
// Qui si costruiscono tutti i payload JSON esposti all'app.
namespace CommProtocol
{
    // Costruisce la busta JSON comune a tutte le risposte strutturate.
    String buildEnvelope(const String& payloadType, const String& requestId, const String& status, const String& payload);
    // Costruisce una risposta errore uniforme per il protocollo BLE.
    String makeErrorJson(const String& requestId, const String& command, const String& message);
    // Restituisce il payload informativo statico del firmware e della board,
    // incluso il metadata OTA con build, timestamp e versione protocollo.
    String makeInfoJson(const String& requestId = "");
    // Restituisce lo stato corrente del flusso OTA via BLE.
    String makeOtaStatusJson(const CommTransportRuntime& runtime);
    // Serializza la telemetria runtime nel formato compact del protocollo,
    // inclusi i metadati istantanei IMU e il sottoinsieme GPS compact
    // esposto all'app quando il parser NAV-PVT e attivo.
    String makeRuntimeStateJson(const RuntimeState& runtimeState);
    // Serializza lo stato attuale della calibrazione sensore.
    String makeCalibrationJson(const CalibrationData& calibration, const String& requestId = "");
    // Serializza lo zero di riferimento attualmente attivo.
    String makeReferenceJson(const ReferenceData& reference, const String& requestId = "");
    // Serializza la configurazione utente esposta all'app, inclusa la mappa assi,
    // e lo stato essenziale del trasporto.
    String makeConfigJson(const AppConfig& config, const CommTransportRuntime& runtime, const String& requestId = "");
    // Serializza lo stato operativo complessivo del firmware senza metriche peak runtime,
    // inclusa la presenza del modulo GPS, lo stato del parser NAV-PVT
    // e l'indicazione di usabilita corrente del fix.
    String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId = "");
}
