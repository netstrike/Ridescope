# Changelog

## 2026-05-17
- aggiunto script PowerShell `scripts/build-firmware.ps1` per compilare usando il PlatformIO corretto anche quando `python` nel PATH non e compatibile
- aggiunta workflow GitHub Actions per compilare il firmware ESP32-C6 e pubblicare `firmware.bin` / `manifest.json` come artifact
- aggiunto `requirements.txt` firmware per rendere esplicita l'installazione PlatformIO nella CI GitHub
- aggiunta pubblicazione GitHub Release automatica sui tag `firmware-*`, con allegati `firmware.bin` e `manifest.json`
- aggiunta workflow GitHub Pages per pubblicare l'ultima build firmware taggata su URL stabili OTA

## 2026-05-16
- chiarito che il GPS, quando presente con stream e fix valido/fresco, puo essere usato come ausilio nei calcoli dei filtri
- aggiornato il fallback esplicito a modalita IMU-only quando GPS o fix non sono disponibili
- allineata la documentazione operativa e architetturale alla pipeline IMU-first con ausilio GNSS opzionale
- preparato il firmware per il versionamento pubblico disabilitando il publish FTPS automatico di default
- rimosse le credenziali FTP di default da `platformio.ini`; le credenziali devono essere fornite tramite variabili ambiente

## 2026-03-29
- il firmware ora abilita e legge il timestamp hardware interno del `LSM6DSOX` insieme a ogni campione accelerometro/giroscopio
- il firmware legge ora anche la temperatura interna della IMU e la propaga nello stato runtime
- il `dt` dei filtri e la `sampleRateHz` usano in modo prioritario il timestamp del sensore, con fallback automatico a `millis()` se il timestamp del campione non e valido
- il payload live compact espone ora `its` per il timestamp hardware IMU e `itc` per la temperatura interna in gradi Celsius
- durante un OTA BLE in corso il firmware sospende le notify `live_tx` per non sottrarre banda al trasferimento del firmware
- il protocollo BLE e stato portato alla versione `4.6`
- aggiornata la documentazione tecnica, inclusi gli esempi protocollo, sul nuovo flusso temporale e termico dei campioni IMU
- chiarita la regola operativa precedente sui diff manuali, ora sostituita dallo storico Git

## 2026-03-30
- aggiunta una predisposizione UART dedicata per un modulo GNSS esterno tipo `SAM-M10Q`, con `GPIO19` come RX ESP32 da `TX GPS` e `GPIO20` come TX ESP32 verso `RX GPS`
- il firmware inizializza la UART GPS a `9600` baud ma non introduce ancora parsing NMEA/UBX, campi BLE o logica GPS applicativa
- aggiornata la documentazione hardware e operativa per chiarire il perimetro della predisposizione GPS

## 2026-03-31
- il firmware configura ora a boot il `SAM-M10Q` via `UBX-CFG-VALSET` per usare `UBX-NAV-PVT` a `10 Hz`, modello dinamico `Automotive` e UART operativa a `115200`
- il bootstrap GPS non assume piu `9600` come default: prova i baudrate tipici del modulo, incluso il default `38400`
- la selezione runtime delle costellazioni GNSS viene ridotta per sostenere il target `10 Hz`
- il firmware drena la UART GPS per non saturare il buffer finche non esiste un parser GNSS reale
- chiarito in documentazione e regole operative che il `high CPU clock` del `SAM-M10Q` non viene scritto in `OTP` automaticamente, perche sarebbe una modifica permanente e irreversibile del modulo
- il payload `status` espone ora `gps_present` per permettere all'app di capire se il modulo GPS e realmente presente sulla UART dedicata
- l'indicazione utile per l'app non e piu una capability statica del live: il payload `live` usa `gok` e lo `status` usa `gps_usable` per segnalare se i dati GPS sono davvero validi e usabili
- il firmware integra ora un parser binario `UBX-NAV-PVT` del `SAM-M10Q`, mantiene l'ultimo fix GNSS in memoria e rileva se lo stream GPS e fresco o assente
- il payload `status` espone anche `gps_configured`, `gps_streaming`, `gps_usable`, `gps_fix_type`, `gps_num_sv` e `gps_fix_age_ms` come diagnostica tecnica del parser GPS
- il payload `live` espone ora anche un sottoinsieme compact dei dati GPS `NAV-PVT`; il protocollo sale alla versione `4.11`
