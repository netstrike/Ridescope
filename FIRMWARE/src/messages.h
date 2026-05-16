#pragma once

#include <Arduino.h>

// Catalogo centralizzato dei testi mostrati dal firmware.
// Qui vanno tenuti:
// - messaggi di log tecnico su seriale
// - status ed errori del protocollo BLE anche quando lo schema payload evolve
// - nomi e semantica dei campi compact live documentati altrove, inclusi its / itc
//   e l'estensione GPS compact
// - campi di stato GPS come gps_present e gps_usable
// - descrizioni dei tipi attesi in validazione JSON
namespace Messages
{
    namespace Log
    {
        // Messaggio di boot del firmware.
        extern const char* const kBootFirmware;
        // Messaggio emesso quando il firmware parte senza una IMU valida.
        extern const char* const kImuMissing;
        // Messaggio emesso quando il sensore viene trovato all'indirizzo 0x6A.
        extern const char* const kImuFound6A;
        // Messaggio emesso quando il sensore viene trovato all'indirizzo 0x6B.
        extern const char* const kImuFound6B;
        // Messaggio emesso quando il sensore non risponde su I2C.
        extern const char* const kImuNotFound;
        // Messaggio di errore in scrittura del registro CTRL3_C.
        extern const char* const kCtrl3WriteFailed;
        // Messaggio di errore in scrittura del registro CTRL1_XL.
        extern const char* const kCtrl1WriteFailed;
        // Messaggio di errore in scrittura del registro CTRL2_G.
        extern const char* const kCtrl2WriteFailed;
        // Messaggio di errore in configurazione del timestamp hardware IMU.
        extern const char* const kTimestampInitFailed;
        // Messaggio di errore in scrittura del registro INT1_CTRL.
        extern const char* const kInt1WriteFailed;
        // Messaggio emesso quando la IMU smette di rispondere su I2C a runtime.
        extern const char* const kImuRuntimeDisconnected;
        // Messaggio emesso quando il recovery automatico della IMU va a buon fine.
        extern const char* const kImuRuntimeRecovered;
        // Messaggio emesso quando parte la configurazione della UART GPS dedicata.
        extern const char* const kGpsUartStarted;
        // Messaggio emesso quando la configurazione runtime del SAM-M10Q va a buon fine.
        extern const char* const kGpsConfigured;
        // Messaggio emesso quando la configurazione runtime del SAM-M10Q non viene confermata.
        extern const char* const kGpsConfigFailed;
        // Messaggio emesso quando il parser rileva stream UBX-NAV-PVT dal GPS.
        extern const char* const kGpsNavPvtStreaming;
        // Messaggio emesso quando il flusso NAV-PVT si interrompe oltre la soglia di freshness.
        extern const char* const kGpsNavPvtTimeout;
        // Messaggio emesso quando l'inizializzazione BLE fallisce.
        extern const char* const kBleInitFailed;
        // Messaggio emesso quando il layer BLE e pronto e in advertising.
        extern const char* const kBleAdvertisingStarted;
        // Messaggio emesso quando un client BLE si connette.
        extern const char* const kBleClientConnected;
        // Messaggio emesso quando il client BLE si disconnette.
        extern const char* const kBleClientDisconnected;
        // Messaggio emesso quando il pairing BLE viene autenticato con successo.
        extern const char* const kBleAuthenticationOk;
        // Messaggio emesso quando il pairing BLE fallisce.
        extern const char* const kBleAuthenticationFailed;
        // Messaggio emesso quando il client sottoscrive la characteristic live.
        extern const char* const kBleLiveSubscribed;
        // Messaggio emesso quando il client rimuove la subscribe live.
        extern const char* const kBleLiveUnsubscribed;
        // Messaggio emesso al primo invio live dopo una subscribe attiva.
        extern const char* const kBleLiveStreamingStarted;
        // Messaggio emesso quando parte un aggiornamento OTA via BLE.
        extern const char* const kBleOtaStarted;
        // Messaggio emesso quando l'OTA BLE termina con successo.
        extern const char* const kBleOtaCompleted;
        // Messaggio emesso quando l'OTA BLE viene abortito.
        extern const char* const kBleOtaAborted;
    }

    namespace Protocol
    {
        // Valori condivisi del campo "status" nel protocollo applicativo.
        extern const char* const kStatoOk;
        extern const char* const kStatoErrore;
        extern const char* const kStatoAccettato;
        extern const char* const kJsonNonValido;
        extern const char* const kComandoSconosciuto;
        extern const char* const kCalibrazioneNonAvviata;
        extern const char* const kUploadInterrotto;
        extern const char* const kNessunCampoCalibrazione;
        extern const char* const kNessunCampoRiferimento;
        extern const char* const kComandoBleOccupato;
        extern const char* const kOtaGiaInCorso;
        extern const char* const kOtaNonInCorso;
        extern const char* const kOtaSizeNonValida;
        extern const char* const kOtaChunkTroppoGrande;
        extern const char* const kOtaChunkFuoriRange;
        extern const char* const kOtaBufferSaturo;
        extern const char* const kOtaWriteFallita;

        // Costruisce il messaggio di errore per un campo stringa obbligatorio.
        String campoObbligatorioStringa(const char* key);
        // Costruisce il messaggio di errore per un campo presente ma di tipo errato.
        String campoDeveEssere(const char* key, const char* expectedType);
        // Costruisce il messaggio di errore per valori attesi nel range [0,1].
        String campoIntervallo01(const char* key);
        // Costruisce il messaggio di errore per valori attesi >= 0.
        String campoMaggioreUgualeZero(const char* key);
        // Costruisce il messaggio di errore per confronti tra due campi numerici.
        String campoMaggioreUgualeAltro(const char* key, const char* otherKey);
        // Costruisce l'errore per un numero di campioni nullo in calibrazione.
        String samplesMaggioreDiZero();
        // Costruisce l'errore per un numero di campioni oltre il range uint16_t.
        String samplesMax65535();
        // Costruisce l'errore per un delay calibrazione oltre il range uint16_t.
        String delayMsMax65535();
        // Costruisce l'errore per periodo telemetria troppo basso.
        String telemetryPeriodTroppoBasso();
        // Costruisce l'errore per un token asse non riconosciuto.
        String campoAsseSensoreNonValido(const char* key);
        // Costruisce l'errore per una mappa assi che riusa lo stesso asse piu volte.
        String mappaAssiSensoreNonValida();
        // Costruisce l'errore per selettore assi non riconosciuto.
        String assiZeroNonValidi();
    }

    namespace JsonTypes
    {
        // Frammenti riusabili per costruire messaggi di validazione coerenti.
        extern const char* const kStringa;
        extern const char* const kBooleano;
        extern const char* const kNumero;
        extern const char* const kInteroSenzaSegno;
    }
}
