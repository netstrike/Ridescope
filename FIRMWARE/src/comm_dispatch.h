#pragma once

#include "comm_shared.h"

#include <ArduinoJson.h>

// Layer applicativo del protocollo:
// valida i payload ricevuti e li traduce in modifiche allo stato firmware.
namespace CommDispatch
{
    // Applica una patch JSON alla configurazione utente con validazione dei campi
    // e delle dipendenze incrociate, come la mappa assi completa.
    bool applyConfigFromJson(const JsonObjectConst& root, AppConfig& config, String& errorMessage);
    // Applica gli offset di calibrazione ricevuti via protocollo.
    bool applyCalibrationFromJson(const JsonObjectConst& root, CalibrationData& calibration, String& errorMessage);
    // Applica uno zero di riferimento ricevuto via protocollo.
    bool applyReferenceFromJson(const JsonObjectConst& root, ReferenceData& reference, String& errorMessage);
}
