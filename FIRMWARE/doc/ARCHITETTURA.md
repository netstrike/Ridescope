# Architettura del firmware

## Scopo
Il firmware implementa una pipeline compatta per la stima dell'inclinazione della moto a partire dal sensore LSM6DSOX, con trasporto applicativo solo BLE.
Una UART dedicata per un modulo GNSS esterno puo essere configurata a bordo, ma i dati GPS non entrano nella pipeline firmware attuale.

## Flusso generale
```text
LSM6DSOX + timestamp hardware + temperatura interna -> calibrazione sensore -> remap frame sensore->veicolo -> prefiltri -> stima roll/pitch -> zero riferimento -> telemetria BLE / OTA BLE
```

## Moduli e responsabilita

### `inclinometro_esp32.ino`
Coordina il sistema:
- avvio seriale
- caricamento NVS
- inizializzazione sensore
- inizializzazione UART GPS opzionale
- inizializzazione BLE
- avvio task dedicato alla misura
- loop principale riservato a comandi BLE, OTA e telemetria

### `sensors.*`
Gestisce il sensore fisico:
- rilevamento indirizzo I2C `0x6A` / `0x6B`
- configurazione registri LSM6DSOX
- lettura raw accelerometro, giroscopio, timestamp hardware e temperatura interna
- applicazione offset di calibrazione
- calibrazione cooperativa non bloccante
- rilevamento fault runtime I2C con degradazione immediata di `imu_ready`
- recovery automatico periodico del sensore

### `gps.*`
Gestisce la configurazione runtime del `SAM-M10Q`:
- rilevamento del baudrate iniziale della UART GPS
- riconfigurazione via `UBX-CFG-VALSET` a `115200`
- impostazione `10 Hz`, `UBX-NAV-PVT`, modello dinamico `Automotive`
- selezione runtime delle costellazioni per sostenere `10 Hz`
- parsing binario dei frame `UBX-NAV-PVT`
- mantenimento dell'ultimo fix GNSS per sola diagnostica tecnica
- nessun uso dei dati GPS in filtri o nel payload `live`

### `filters.*`
Implementa la stima inclinazione:
- rimappatura del frame sensore nel frame veicolo canonico
- low-pass accelerometro
- low-pass giroscopio
- calcolo `dt` prioritariamente dal timestamp hardware IMU, con fallback a `millis()`
- correzione lenta del bias residuo gyro in condizioni quasi statiche
- calcolo angoli da accelerometro
- stima semplificata dell'accelerazione longitudinale
- filtro complementare
- trust adattivo basato su `|a| ~= 1 g`
- zero di riferimento

### `storage.*`
Gestisce la persistenza NVS:
- configurazione
- calibrazione
- riferimento zero
- rimozione chiavi legacy del vecchio trasporto Wi-Fi

### `comm.*`
Espone le interfacce di comunicazione:
- `comm.cpp`: facade usata dal resto del firmware
- `comm_transport.*`: server BLE GATT, pairing, code interne e OTA BLE
- `comm_dispatch.*`: validazione e applicazione comandi/config
- `comm_protocol.*`: costruzione payload JSON
- `comm_json.*`: parsing JSON tipizzato

### `status_led.*`
Gestisce la diagnostica locale su LED RGB:
- verde lampeggiante se BLE e IMU reale sono sani
- rosso lampeggiante in presenza di fail reali
- blu lampeggiante durante OTA

## Trasporto BLE
Il firmware espone un servizio GATT custom con:
- `command_rx` per comandi JSON in write
- `response_tx` per risposte JSON in notify
- `live_tx` per telemetria `compact` in notify
- `ota_data_rx` per chunk binari firmware

Pairing e sicurezza:
- passkey statica
- bonding attivo
- MITM attivo
- secure connection attiva

Politica di sessione:
- un solo client BLE alla volta
- l'autenticazione viene richiesta alla connessione
- la disconnessione durante OTA abortisce il trasferimento

## Code e sincronizzazione
Il trasporto BLE non scrive direttamente su `AppConfig`, `CalibrationData` o `ReferenceData` dalle callback GATT.

Schema:
- le callback BLE restano leggere
- i comandi JSON vengono copiati in una coda interna
- il loop principale estrae la richiesta e la applica sullo snapshot condiviso
- i chunk OTA vengono accodati e scritti su flash dal loop principale

Questo evita race tra callback BLE, task misura e stato applicativo globale.

## OTA BLE
Flusso:
1. comando `ota_begin` con dimensione attesa
2. stream binario su `ota_data_rx`
3. comando `ota_end`
4. `Update.end(true)`
5. reboot differito per permettere l'ultima risposta BLE

Condizioni di abort:
- disconnessione client
- saturazione buffer OTA
- chunk oltre la dimensione dichiarata
- errore `Update.write()` o `Update.end()`

Durante un OTA in corso le notify `live_tx` vengono sospese per lasciare banda BLE al trasferimento dei chunk firmware.

## Payload

### `info`
Contiene dati statici:
- build firmware, timestamp build e versione protocollo
- profilo board e sensore
- UUID BLE correnti
- pairing richiesto
- supporto OTA BLE

### `status`
Contiene stato runtime:
- stato BLE
- stato OTA
- stato calibrazione e riferimento
- health sensore e indirizzo I2C
- presenza rilevata del modulo GPS opzionale
- stato del parser GPS (`gps_configured`, `gps_streaming`, `gps_usable`, `gps_fix_type`, `gps_num_sv`, `gps_fix_age_ms`)
- modalita data-ready

### `live`
Contiene solo i dati ad alta frequenza:
- timestamp runtime firmware
- timestamp hardware IMU
- roll / pitch
- varianti raw
- accelerazione longitudinale
- temperatura interna IMU
- stato ready IMU reale
- sottoinsieme compact dei dati GPS `NAV-PVT`

I campi legacy `mrl`, `mrr`, `psr`, `maf`, `mdb`, `ag` e `at` non vengono piu trasmessi.

## Frame canonico veicolo
Il firmware usa un frame canonico costante:
- `X` laterale
- `Y` longitudinale
- `Z` verticale

La configurazione `body_lateral_axis`, `body_longitudinal_axis` e `body_vertical_axis`
rimappa gli assi fisici del sensore nel frame canonico prima dei prefiltri e del filtro complementare.
Il default corrente coincide con l'orientamento storico:
- `+x`, `+y`, `+z`

Il remap fisico del sensore avviene solo tramite la tripletta `body_*_axis`.

## Politica firmware / app
- nel firmware devono restare solo misura, trasporto BLE e OTA
- la UART GPS opzionale e ammessa per configurazione tecnica del ricevitore e parser diagnostico minimo, ma senza logica GPS applicativa
- UX, GPS, dati viaggio, log sessione e analisi restano nell'app
- non reintrodurre Wi-Fi, HTTP o WebSocket senza una nuova decisione architetturale esplicita
