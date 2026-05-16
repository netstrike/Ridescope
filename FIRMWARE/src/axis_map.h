#pragma once

#include "types.h"

#include <Arduino.h>

namespace AxisMap
{
    // Converte il token protocollo in un selettore interno.
    bool parseAxisRef(const String& value, AxisRef& axisRef);
    // Restituisce il token protocollo canonico associato all'asse.
    const char* toString(AxisRef axisRef);
    // Valida che la mappa usi X, Y e Z una sola volta ciascuno.
    bool isAxisMapValid(const AxisMapConfig& axisMap);
    // Rimappa un campione IMU dal frame fisico del sensore al frame veicolo canonico.
    RawImuData mapToBodyFrame(const AxisMapConfig& axisMap, const RawImuData& sensorFrame);
}
