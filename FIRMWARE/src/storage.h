// Interfaccia del modulo NVS.
// Questo modulo salva e ricarica parametri persistenti:
// configurazione applicativa, mappa assi sensore, calibrazione e riferimento zero.

#pragma once

#include "types.h"

namespace Storage
{
    // Inizializza il namespace NVS del firmware.
    void begin();
    // Carica da NVS la configurazione persistita.
    void loadConfig(AppConfig& config);
    // Salva in NVS la configurazione persistita.
    void saveConfig(const AppConfig& config);
    // Stampa in seriale la configurazione per supporto tecnico.
    void printConfig(const AppConfig& config);

    // Carica da NVS la calibrazione sensore persistita.
    void loadCalibration(CalibrationData& calibration);
    // Salva in NVS la calibrazione sensore corrente.
    void saveCalibration(const CalibrationData& calibration);
    // Rimuove completamente la calibrazione persistita.
    void clearCalibration();
    // Stampa in seriale la calibrazione per supporto tecnico.
    void printCalibration(const CalibrationData& calibration);

    // Carica da NVS il riferimento zero persistito.
    void loadReference(ReferenceData& reference);
    // Salva in NVS il riferimento zero corrente.
    void saveReference(const ReferenceData& reference);
    // Rimuove lo zero utente persistito.
    void clearReference();
    // Stampa in seriale il riferimento zero per supporto tecnico.
    void printReference(const ReferenceData& reference);
}
