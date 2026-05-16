// Interfaccia del motore di stima dell'inclinazione.
// Il filtro combina accelerometro e giroscopio per ottenere roll e pitch.

#pragma once

#include "types.h"

namespace Filters
{
    // Inizializza lo stato interno dei filtri.
    void begin(FilterState& state);
    // Aggiorna la stima di assetto e i valori runtime a partire dall'ultimo campione IMU,
    // usando prioritariamente il timestamp hardware del sensore per il dt
    // e propagando nel live anche timestamp e temperatura IMU
    // dopo il remap nel frame veicolo canonico.
    // gpsAid può essere un GpsAidData con valid=false se il GPS non è disponibile:
    // la pipeline degrada automaticamente al comportamento IMU-only senza effetti collaterali.
    void update(const AppConfig& config, const RawImuData& imuData, const ReferenceData& reference, const GpsAidData& gpsAid, FilterState& state, RuntimeState& runtimeState);
}
