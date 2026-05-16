#pragma once

#include <HardwareSerial.h>

#include "types.h"

// Modulo di configurazione UART per un ricevitore GNSS esterno tipo SAM-M10Q.
// Il firmware configura il ricevitore a runtime via UBX e ne decodifica i frame NAV-PVT,
// senza usare i dati GPS nei filtri di inclinazione o nel payload live.
namespace Gps
{
    // Inizializza e configura la UART dedicata al modulo GNSS se abilitata dal profilo hardware.
    void begin();
    // Fa avanzare il parser UBX e aggiorna l'ultimo fix GPS disponibile.
    void update();
    // Indica se la predisposizione UART GPS e attiva nel profilo corrente.
    bool enabled();
    // Indica se il modulo GNSS risponde sulla UART ed e stato rilevato dal firmware.
    bool present();
    // Indica se la configurazione runtime del SAM-M10Q e stata confermata via UBX.
    bool configured();
    // Indica se il firmware sta ricevendo frame NAV-PVT recenti dal modulo.
    bool streaming();
    // Copia l'ultimo fix NAV-PVT decodificato se disponibile.
    bool readFix(GpsFixData& out);
    // Restituisce l'eta del fix piu recente in millisecondi.
    uint32_t fixAgeMs();
    // Espone la UART dedicata per eventuali moduli diagnostici futuri.
    HardwareSerial& serial();
}
