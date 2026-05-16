# RideScope - README app Android

## Base di partenza
Questo progetto deriva dal template Android Studio fornito dall'utente ed e stato convertito in app Kotlin + Jetpack Compose.

## Metadata build app
Ad ogni build applicativa completa RideScope aggiorna automaticamente:
- `build` app in formato semver
- `timestamp` build in formato `YYYYMMDD.HHMMSS`
- `protocol` applicativo

Gli stessi campi vengono:
- esposti dentro l'app nella pagina `Aggiornamento`
- scritti in `BuildConfig`
- serializzati nel file `manifest.json` con root `ridescope`

Esempio:
```json
{
  "ridescope": {
    "build": "1.0.1",
    "timestamp": "20260321.193224",
    "protocol": "4.11"
  }
}
```

Output build locale:
- `app/build/outputs/ridescope/manifest.json`
- `app/build/outputs/apk/<variant>/ridescope.apk`

Pubblicazione automatica:
- dopo i task applicativi che producono o installano l'APK, RideScope copia via FTP `ridescope.apk` e `manifest.json`
- repository remoto app: `ftp://192.168.1.3/ftp/ridescope/app`
- ordine di upload: prima `ridescope.apk`, poi `manifest.json`
- variabili ambiente di configurazione: `RIDESCOPE_APP_FTP_HOST`, `RIDESCOPE_APP_FTP_PORT`, `RIDESCOPE_APP_FTP_DIRECTORY`, `RIDESCOPE_APP_FTP_USER`, `RIDESCOPE_APP_FTP_PASSWORD`
- se `RIDESCOPE_APP_FTP_USER` o `RIDESCOPE_APP_FTP_PASSWORD` non sono configurate, il publish FTP viene saltato
- `RIDESCOPE_SKIP_APP_FTP_PUBLISH=1` permette di saltare il publish in build locali offline

## Schermate presenti
- Telemetria
- Impostazioni
- Filtri
- Calibrazione
- Aggiornamento
- Diagnostica

## Documentazione dedicata
- ricostruzione del gauge PFD Airbus-like: `doc/PFD_AIRBUS_GAUGE.md`

## Firmware atteso
- trasporto BLE GATT custom
- device name: `RideScope`
- service UUID: `58f2a0f5-3d5e-4697-b3c9-67dbe7f5a501`
- characteristic `command_rx`: `58f2a0f5-3d5e-4697-b3c9-67dbe7f5a502`
- characteristic `response_tx`: `58f2a0f5-3d5e-4697-b3c9-67dbe7f5a503`
- characteristic `live_tx`: `58f2a0f5-3d5e-4697-b3c9-67dbe7f5a504`
- pairing richiesto con passkey statica `123456`

## Convenzione assi canonica
Fanno fede `doc/HARDWARE.md` e `doc/README.md` del firmware:
- asse Y longitudinale verso l'anteriore
- asse X trasversale verso destra
- asse Z verso l'alto

## Telemetria usata
Lo stream live BLE usa i campi compact del firmware:
- `ts`
- `its`
- `r`
- `p`
- `rr`
- `rp`
- `ay`
- `itc`
- `ir`
- `git`
- `gok`
- `gft`
- `gsv`
- `glon`
- `glat`
- `gmsl`
- `gha`
- `gvn`
- `gve`
- `gvd`
- `gsp`
- `ghm`
- `gsa`
- `ghd`

I picchi di roll, pitch, accelerazione e frenata sono gestiti localmente dall'app e non vengono piu letti dal firmware.

## Configurazione firmware
- l'app modifica solo i campi realmente editabili dal firmware
- la configurazione tecnica viene inviata come comando BLE `set_config`
- il protocollo firmware `4.11` non include piu `debug_fake_telemetry_enabled`, quindi l'app non lo mostra e non lo invia piu
- la pagina `Impostazioni` espone anche la mappa assi `body_lateral_axis` / `body_longitudinal_axis` / `body_vertical_axis`
- la pagina `Impostazioni` espone anche limiti locali dei gauge telemetria app-side: roll, pitch, accelerazione longitudinale e speed GPS
- il gauge AY puo essere visualizzato in `g` oppure in `m*s^2`, con aggiornamento coerente di valore realtime e scala
- la pagina `Impostazioni` espone anche un toggle app-side per mantenere lo schermo sempre acceso oppure lasciarlo gestire dal sistema
- la pagina `Impostazioni` espone anche la configurazione FTP del repository firmware, un percorso FTP dedicato per i manifest e l'APK dell'app e l'intervallo di controllo
- la pagina `Aggiornamento` espone la UI operativa per verifica versioni, stato aggiornamento, progresso OTA e azioni `CONTROLLA ORA` / `UPD`
- cambiando il limite del gauge pitch, l'app aggiorna sia le etichette scala sia l'ampiezza dell'arco
- la pagina `Filtri` contiene i parametri tecnici dei filtri firmware

## Note pratiche
- il firmware accetta un solo client BLE alla volta
- `response_tx` e `live_tx` sono notify BLE terminate da newline
- la pagina `Diagnostica` non mostra piu metadati versione app/firmware: il riferimento canonico per build e protocollo e la pagina `Aggiornamento`
- la pagina `Diagnostica` mostra anche `its`, `itc` e lo stato GNSS firmware/app dell'ultimo campione live ricevuto
- il controllo aggiornamenti firmware avviene lato app confrontando i metadati correnti letti via BLE con il `manifest.json` remoto pubblicato sul repository FTP
- lo stesso pulsante `CONTROLLA ORA` confronta anche la build locale dell'app con il `manifest.json` remoto pubblicato nel repository app, di default `ftp/ridescope/app`
- quando il manifest remoto contiene una revisione piu nuova, l'app scarica `firmware.bin` dal repository FTP e lo trasferisce al dispositivo con `ota_begin -> chunk su ota_data_rx -> ota_end`
- durante l'OTA BLE l'app usa `WRITE_NR` su `ota_data_rx` quando il firmware lo espone, ma con pacing piu conservativo e fallback automatico alle write con risposta se il client Android segnala backlog GATT; il drain resta compatibile con la coda firmware da `8` chunk
- quando il manifest remoto dell'app contiene una build piu nuova, il pulsante `AGGIORNA APPLICAZIONE` scarica `ridescope.apk` dal repository FTP, lo salva in cache e apre l'installer Android sul device
- se Android blocca il sideload, RideScope apre direttamente la schermata di sistema per abilitare l'installazione app per questa applicazione e poi richiede di riprovare
- roll, pitch e AY usano solo il sensore esterno via firmware
- velocita, trip e max speed preferiscono il GNSS compact del firmware; il GPS del telefono resta un fallback locale quando non c'e un fix firmware usabile
- la pagina `Telemetria` include anche un modulo recording locale con export XML di posizione, velocita, altitudine, roll, pitch, accel, decel e riepilogo finale sessione
- con schermo sempre attivo disabilitato, l'app usa un `PARTIAL_WAKE_LOCK` durante la registrazione per continuare a campionare e salvare dati anche a schermo spento
- la pagina `Registrazioni` mostra i file XML salvati, i dati sintetici della sessione, permette l'eliminazione dal dispositivo e l'export in CSV / GPX / FIT
- l'export FIT usa il Garmin FIT SDK ufficiale e genera un file activity standard con timestamp, distanza, velocita, altitudine, posizione GPS, lap, sessione e sport `motorcycling`
- i campi IMU proprietari RideScope come roll, pitch, accel e decel restano completi negli export XML / CSV / GPX; non vengono ancora serializzati come developer fields nel FIT
- il progetto non include ancora mappe

## Deploy rapido su dispositivo fisico
Dalla root del progetto:
`powershell -ExecutionPolicy Bypass -File .\scripts\deploy-device.ps1`

Opzioni utili:
- `-Serial <id>` per scegliere un device specifico se ce ne sono piu di uno
- `-GradleTask installDebug` per cambiare task Gradle se serve
