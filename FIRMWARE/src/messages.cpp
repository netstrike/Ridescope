#include "messages.h"

// Implementazione del catalogo testi del firmware.
// Tenere qui le stringhe evita hardcode sparsi e rende piu semplice
// uniformare tono, lingua e documentazione del protocollo anche quando il payload cambia,
// inclusa l'estensione del compact live, i campi GPS compact e lo stato GPS dichiarato all'app.
namespace Messages
{
    namespace Log
    {
        const char* const kBootFirmware = "Avvio firmware inclinometro ESP32";
        const char* const kImuMissing = "Firmware avviato senza IMU valida: controllare cablaggio LSM6DSOX";
        const char* const kImuFound6A = "LSM6DSOX trovato su I2C 0x6A";
        const char* const kImuFound6B = "LSM6DSOX trovato su I2C 0x6B";
        const char* const kImuNotFound = "ERRORE: LSM6DSOX non trovato su I2C";
        const char* const kCtrl3WriteFailed = "ERRORE: scrittura CTRL3_C fallita";
        const char* const kCtrl1WriteFailed = "ERRORE: scrittura CTRL1_XL fallita";
        const char* const kCtrl2WriteFailed = "ERRORE: scrittura CTRL2_G fallita";
        const char* const kTimestampInitFailed = "ERRORE: configurazione timestamp LSM6DSOX fallita";
        const char* const kInt1WriteFailed = "ERRORE: scrittura INT1_CTRL fallita";
        const char* const kImuRuntimeDisconnected = "ERRORE: comunicazione I2C con LSM6DSOX persa";
        const char* const kImuRuntimeRecovered = "LSM6DSOX nuovamente operativo dopo recovery automatico";
        const char* const kGpsUartStarted = "Configurazione SAM-M10Q su UART GPS in corso";
        const char* const kGpsConfigured = "SAM-M10Q configurato: UBX-NAV-PVT 10 Hz, UART 115200, modello Automotive";
        const char* const kGpsConfigFailed = "ATTENZIONE: configurazione SAM-M10Q non confermata";
        const char* const kGpsNavPvtStreaming = "SAM-M10Q: stream UBX-NAV-PVT rilevato";
        const char* const kGpsNavPvtTimeout = "ATTENZIONE: stream UBX-NAV-PVT assente o fermo";
        const char* const kBleInitFailed = "ERRORE: inizializzazione BLE fallita";
        const char* const kBleAdvertisingStarted = "BLE pronto: advertising attiva";
        const char* const kBleClientConnected = "Client BLE connesso";
        const char* const kBleClientDisconnected = "Client BLE disconnesso";
        const char* const kBleAuthenticationOk = "Pairing BLE completato con successo";
        const char* const kBleAuthenticationFailed = "ERRORE: pairing BLE fallito";
        const char* const kBleLiveSubscribed = "Client BLE sottoscritto a live_tx";
        const char* const kBleLiveUnsubscribed = "Client BLE non piu sottoscritto a live_tx";
        const char* const kBleLiveStreamingStarted = "Streaming live BLE avviato";
        const char* const kBleOtaStarted = "OTA BLE avviato";
        const char* const kBleOtaCompleted = "OTA BLE completato: riavvio pianificato";
        const char* const kBleOtaAborted = "OTA BLE interrotto";
    }

    namespace Protocol
    {
        const char* const kStatoOk = "ok";
        const char* const kStatoErrore = "errore";
        const char* const kStatoAccettato = "accettato";
        const char* const kJsonNonValido = "json_non_valido";
        const char* const kComandoSconosciuto = "comando_sconosciuto";
        const char* const kCalibrazioneNonAvviata = "calibrazione_non_avviata";
        const char* const kUploadInterrotto = "upload_interrotto";
        const char* const kNessunCampoCalibrazione = "nessun campo di calibrazione presente";
        const char* const kNessunCampoRiferimento = "nessun campo di riferimento presente";
        const char* const kComandoBleOccupato = "richiesta_ble_precedente_non_ancora_gestita";
        const char* const kOtaGiaInCorso = "aggiornamento_ota_gia_in_corso";
        const char* const kOtaNonInCorso = "nessun_aggiornamento_ota_in_corso";
        const char* const kOtaSizeNonValida = "il campo size deve essere > 0";
        const char* const kOtaChunkTroppoGrande = "chunk_ota_ble_troppo_grande";
        const char* const kOtaChunkFuoriRange = "chunk_ota_oltre_dimensione_dichiarata";
        const char* const kOtaBufferSaturo = "buffer_ota_ble_saturo";
        const char* const kOtaWriteFallita = "scrittura_ota_ble_fallita";

        // Costruisce il messaggio di errore per un campo stringa obbligatorio.
        String campoObbligatorioStringa(const char* key)
        {
            return String("il campo ") + key + " deve essere una stringa";
        }

        // Costruisce il messaggio di errore per un campo presente ma di tipo errato.
        String campoDeveEssere(const char* key, const char* expectedType)
        {
            return String("il campo ") + key + " deve essere " + expectedType;
        }

        // Costruisce il messaggio di errore per valori attesi nel range [0,1].
        String campoIntervallo01(const char* key)
        {
            return String("il campo ") + key + " deve essere compreso tra 0 e 1";
        }

        // Costruisce il messaggio di errore per valori attesi >= 0.
        String campoMaggioreUgualeZero(const char* key)
        {
            return String("il campo ") + key + " deve essere >= 0";
        }

        // Costruisce il messaggio di errore per confronti tra due campi numerici.
        String campoMaggioreUgualeAltro(const char* key, const char* otherKey)
        {
            return String("il campo ") + key + " deve essere >= " + otherKey;
        }

        // Restituisce l'errore quando samples e nullo.
        String samplesMaggioreDiZero()
        {
            return "il campo samples deve essere > 0";
        }

        // Restituisce l'errore quando samples supera il range uint16_t.
        String samplesMax65535()
        {
            return "il campo samples deve essere <= 65535";
        }

        // Restituisce l'errore quando delay_ms supera il range uint16_t.
        String delayMsMax65535()
        {
            return "il campo delay_ms deve essere <= 65535";
        }

        // Restituisce l'errore per periodo telemetria inferiore al minimo ammesso.
        String telemetryPeriodTroppoBasso()
        {
            return "telemetry_period_ms deve essere >= 20";
        }

        // Restituisce l'errore per un token asse protocollo non supportato.
        String campoAsseSensoreNonValido(const char* key)
        {
            return String("il campo ") + key + " deve essere uno tra +x, -x, +y, -y, +z, -z";
        }

        // Restituisce l'errore quando la mappa assi non usa x, y e z una sola volta.
        String mappaAssiSensoreNonValida()
        {
            return "body_lateral_axis, body_longitudinal_axis e body_vertical_axis devono usare x, y e z una sola volta";
        }

        // Restituisce l'errore per selezione assi non supportata.
        String assiZeroNonValidi()
        {
            return "axes deve essere roll, pitch oppure both";
        }
    }

    namespace JsonTypes
    {
        const char* const kStringa = "una stringa";
        const char* const kBooleano = "un booleano";
        const char* const kNumero = "un numero";
        const char* const kInteroSenzaSegno = "un intero senza segno";
    }
}
