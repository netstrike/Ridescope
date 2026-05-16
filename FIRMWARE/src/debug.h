// Interfaccia del modulo di debug seriale.
// Le funzioni qui dichiarate servono solo per log e diagnostica locale.

#pragma once

namespace Debug
{
    // Inizializza il logger; non abilita output automatici.
    void begin();
    // Abilita o disabilita l'emissione dei log seriali.
    void setEnabled(bool enabled);
    // Stampa un messaggio informativo sul canale seriale tecnico.
    void info(const char* message);
}
