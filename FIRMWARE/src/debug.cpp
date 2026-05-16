// Implementazione del debug seriale.
// Manteniamo il formato volutamente semplice per facilitare il troubleshooting sul banco.

#include "debug.h"
#include "config.h"

namespace
{
    bool g_debugEnabled = true;
}

namespace Debug
{
    // Prepara il logger seriale; non ci sono risorse aggiuntive da allocare.
    void begin()
    {
        // Il logger non stampa nulla da solo: i messaggi vengono emessi esplicitamente
        // dai moduli che vogliono lasciare traccia di eventi tecnici.
    }

    // Aggiorna il flag globale che governa l'emissione dei log.
    void setEnabled(bool enabled)
    {
        g_debugEnabled = enabled;
    }

    // Scrive un singolo messaggio informativo sul canale seriale tecnico.
    void info(const char* message)
    {
        if (!g_debugEnabled)
        {
            return;
        }
        TECHNICAL_SERIAL.print("[INFO] ");
        TECHNICAL_SERIAL.println(message);
    }
}
