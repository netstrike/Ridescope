# Firmware ESP32 inclinometro moto

Firmware dedicato solo alla misura di roll, pitch e accelerazione longitudinale semplificata.
GPS, mappe, log viaggio e logiche UX devono restare nell'app.
Il firmware configura a boot un modulo GNSS esterno tipo `SAM-M10Q`, ne decodifica `UBX-NAV-PVT` e pubblica un sottoinsieme compact dei dati GPS nel payload `live`, senza usare il GPS nella logica applicativa di inclinazione.

## Hardware di riferimento
- MCU / board: Waveshare ESP32-C6 Mini Development Board / ESP32-C6-Zero
- Chip: ESP32-C6FH8
- Sensore: breakout Adafruit LSM6DSOX
- Modulo opzionale: ricevitore GNSS UART tipo `SAM-M10Q`
- Interfaccia sensore: I2C
- LED locale di stato: RGB onboard su `GPIO8`

## Obiettivo del firmware
Il firmware si occupa solo di:
- acquisizione IMU
- calibrazione sensore
- rimappatura assi sensore nel frame veicolo
- stima di roll e pitch
- zero meccanico di riferimento
- telemetria live
- configurazione tecnica dei filtri
- OTA firmware via BLE
- configurazione tecnica UART/UBX per un modulo GPS esterno

## Moduli principali
- `inclinometro_esp32.ino` - bootstrap, task misura e loop comunicazione
- `sensors.*` - inizializzazione LSM6DSOX, lettura IMU, recovery runtime, calibrazione
- `gps.*` - configurazione UART/UBX del `SAM-M10Q` a runtime e parsing tecnico di `UBX-NAV-PVT`
- `filters.*` - filtri e stima inclinazione
- `storage.*` - persistenza NVS
- `comm.cpp` - facade del layer comunicazione
- `comm_transport.*` - server BLE GATT, pairing, telemetria notify e OTA BLE
- `comm_dispatch.*` - validazione e applicazione comandi/config
- `comm_protocol.*` - serializzazione JSON
- `comm_json.*` - parsing JSON strutturato
- `status_led.*` - diagnostica locale via LED RGB
- `debug.*` - log seriale tecnici

## Modello di esecuzione
- task misura: acquisizione IMU, filtri e completamento calibrazione
- loop principale: gestione comandi BLE, aggiornamenti OTA e invio telemetria

Il task BLE non modifica direttamente `AppConfig`, calibrazione o riferimento: le write GATT vengono accodate e applicate nel loop principale, cosi il contesto applicativo resta coerente.

## Configurazione GPS UART
Il firmware riserva una UART dedicata per un modulo GNSS esterno tipo `SAM-M10Q`:

- `GPIO19`: RX ESP32 collegato al `TX` del GPS
- `GPIO20`: TX ESP32 collegato al `RX` del GPS
- bootstrap/probe su baudrate tipici del modulo, incluso il default UART `38400`
- baudrate operativo forzato dal firmware: `115200`
- output GNSS configurato a `10 Hz`
- messaggio abilitato su UART: `UBX-NAV-PVT`
- modello dinamico: `Automotive`
- costellazioni runtime usate: `GPS + Galileo`, con `QZSS`/`SBAS` coerenti con GPS
- output `NMEA` disabilitato sulla UART del GPS

Limiti attuali:
- nessun uso dei dati GPS nei filtri di inclinazione
- nessun calcolo firmware basato su GPS
- il firmware espone stato tecnico GNSS nel payload `status` e un sottoinsieme compact nel payload `live`
- la seriale USB resta riservata ai log tecnici del firmware

Nota importante:
- il manuale u-blox descrive il "high CPU clock" del `SAM-M10Q` come configurazione `OTP` permanente e irreversibile; il firmware non lo programma automaticamente

Diagnostica lato app:
- il payload `status` espone `gps_present` per indicare se il modulo GNSS risponde realmente sulla UART dedicata
- il payload `status` espone anche `gps_configured`, `gps_streaming`, `gps_usable`, `gps_fix_type`, `gps_num_sv` e `gps_fix_age_ms`
- il payload `live` contiene sempre i campi GPS compact nello schema corrente; l'app deve usare `gok` per sapere se il fix del campione e davvero usabile

## Trasporto BLE
Il firmware espone un singolo servizio GATT custom con quattro characteristic:

- `command_rx`: write JSON, un comando per write
- `response_tx`: notify JSON di risposta
- `live_tx`: notify telemetria live
- `ota_data_rx`: write chunk binari del firmware

UUID correnti:
- service: `58f2a0f5-3d5e-4697-b3c9-67dbe7f5a501`
- command_rx: `58f2a0f5-3d5e-4697-b3c9-67dbe7f5a502`
- response_tx: `58f2a0f5-3d5e-4697-b3c9-67dbe7f5a503`
- live_tx: `58f2a0f5-3d5e-4697-b3c9-67dbe7f5a504`
- ota_data_rx: `58f2a0f5-3d5e-4697-b3c9-67dbe7f5a505`

### Pairing
- pairing obbligatorio
- modalita: passkey statica
- passkey corrente: `123456`
- bonding abilitato

Il firmware accetta un solo client BLE alla volta. In caso di disconnessione durante OTA, l'aggiornamento viene abortito.

### Framing dei messaggi
- ogni write su `command_rx` contiene un singolo oggetto JSON completo
- `response_tx` e `live_tx` inviano payload JSON terminati da newline
- se il payload supera il singolo pacchetto ATT, il firmware lo spezza in piu notify consecutive

## Comandi supportati
- `ping`
- `get_info`
- `get_config`
- `set_config`
- `get_status`
- `get_live`
- `get_calibration`
- `calibrate`
- `run_calibration`
- `clear_calibration`
- `get_reference`
- `set_reference`
- `set_zero`
- `clear_reference`
- `get_ota_status`
- `ota_begin`
- `ota_end`
- `ota_abort`

Per il controllo OTA lato app, `get_info` espone `firmware.build`, `firmware.timestamp` e `firmware.protocol`.
`firmware.build` incrementa automaticamente a ogni build riuscita.
Ad ogni build PlatformIO viene generato anche `.pio/build/<env>/manifest.json` con gli stessi campi.
Con build PlatformIO riuscita, `firmware.bin` e `manifest.json` vengono anche pubblicati via FTPS nel repository configurato.

## Telemetria live
La telemetria live usa sempre il payload `compact`.

Esempio:
```json
{"ts":123456,"its":5021120,"r":12.34,"p":47.50,"rr":13.10,"rp":47.50,"ay":0.750,"itc":31.25,"ir":true,"git":345678000,"gok":true,"gft":3,"gsv":12,"glon":121234567,"glat":451234567,"gmsl":152340,"gha":1800,"gvn":12500,"gve":-320,"gvd":40,"gsp":12504,"ghm":1823456,"gsa":220,"ghd":9500}
```

Campi:
- `ts`: timestamp runtime in millisecondi
- `its`: timestamp hardware IMU in tick da `25 us`, azzerato a ogni init o recovery della IMU
- `r`, `p`: roll e pitch filtrati
- `rr`, `rp`: roll e pitch raw
- `ay`: accelerazione longitudinale stimata
- `itc`: temperatura interna della IMU in gradi Celsius
- `ir`: stato ready del sensore reale
- `git`: GPS time of week in millisecondi dal frame `UBX-NAV-PVT`
- `gok`: fix GPS usabile secondo `gnssFixOk`, `fixType` e validita LLH
- `gft`: `fixType` u-blox dell'ultimo `NAV-PVT`
- `gsv`: numero satelliti usati nel fix
- `glon`: longitudine in unita native `1e-7 deg`
- `glat`: latitudine in unita native `1e-7 deg`
- `gmsl`: quota MSL in millimetri
- `gha`: accuratezza orizzontale in millimetri
- `gvn`, `gve`, `gvd`: velocita N/E/D in millimetri al secondo
- `gsp`: velocita al suolo in millimetri al secondo
- `ghm`: heading di moto in unita native `1e-5 deg`
- `gsa`: accuratezza della velocita in millimetri al secondo
- `ghd`: accuratezza dell'heading in unita native `1e-5 deg`

Note:
- il payload live continua a usare `ts` come tempo runtime firmware in millisecondi
- `its` e il timestamp hardware del `LSM6DSOX` esposto anche al client, utile per ricostruire il tempo reale del campione lato app
- internamente il `dt` dei filtri e la `sampleRateHz` usano in modo prioritario lo stesso timestamp hardware, con fallback a `millis()` se il campione non porta un timestamp valido
- `itc` descrive la temperatura interna del package IMU, non una misura ambientale calibrata
- i campi GPS del `live` sono pubblicati nelle unita native `UBX-NAV-PVT` per minimizzare conversioni e ambiguita lato app
- durante un OTA in corso il firmware sospende le notify su `live_tx` per non sottrarre banda al trasferimento firmware

## Frame veicolo e mappa assi sensore
Il firmware usa sempre un frame canonico del veicolo:
- `X`: laterale
- `Y`: longitudinale
- `Z`: verticale

I campi di configurazione:
- `body_lateral_axis`
- `body_longitudinal_axis`
- `body_vertical_axis`

rimappano gli assi fisici del LSM6DSOX nel frame canonico usato da filtri e telemetria.

Default attuale:
- `body_lateral_axis = "+x"`
- `body_longitudinal_axis = "+y"`
- `body_vertical_axis = "+z"`

Token ammessi:
- `+x`, `-x`
- `+y`, `-y`
- `+z`, `-z`

La tripletta deve usare `x`, `y` e `z` una sola volta ciascuno.
L'orientamento del sensore si gestisce solo tramite `body_lateral_axis`, `body_longitudinal_axis` e `body_vertical_axis`.

## Dati persistenti in NVS
### Configurazione
- parametri filtri
- mappa assi sensore -> frame veicolo
- `telemetry_period_ms`
- `debug_serial`

### Calibrazione
- offset accelerometro
- offset giroscopio

## Timing campioni IMU
- il firmware abilita il timestamp hardware interno del `LSM6DSOX`
- ogni campione letto da `sensors.*` include il contatore timestamp a 32 bit del sensore
- ogni campione letto da `sensors.*` include anche la temperatura interna della IMU
- `filters.*` usa il timestamp come sorgente primaria del `dt` del filtro complementare e della `sampleRateHz`
- il contatore viene riallineato a zero a ogni init o recovery della IMU

### Riferimento
- zero roll
- zero pitch

Le vecchie chiavi Wi-Fi vengono rimosse automaticamente all'avvio e non fanno piu parte della configurazione firmware. Le vecchie chiavi `invRoll` / `invPitch` non sono piu usate dal firmware.

## OTA BLE
Flusso:
1. `ota_begin` con `size`
2. invio chunk binari su `ota_data_rx`
3. `ota_end`
4. reboot automatico dopo completamento

Note pratiche:
- `size` deve essere > 0
- i chunk devono rispettare il MTU negoziato; con MTU preferito `247`, il payload utile tipico e `244` byte
- mentre `ota_busy` e vero il firmware non invia telemetria `live_tx`, cosi la banda BLE resta disponibile ai chunk firmware
- se il client si disconnette o il buffer OTA satura, il trasferimento viene abortito

## LED di stato
- verde lampeggiante: BLE pronto e IMU reale pronta
- rosso lampeggiante: fail reale del sistema
- blu lampeggiante: OTA in corso o reboot OTA pianificato

## Log seriali tecnici
La seriale resta solo tecnica e puo includere eventi BLE di servizio come:
- advertising avviata
- connessione client
- pairing completato o fallito
- subscribe a `live_tx`
- primo avvio dello streaming live

## Note protocollo pratiche
- `set_config` applica una patch atomica: se una validazione fallisce, nessun campo della patch viene applicato
- `telemetry_period_ms` ha minimo valido `20`
- `body_lateral_axis`, `body_longitudinal_axis` e `body_vertical_axis` devono usare `x`, `y` e `z` una sola volta
- `debug_fake_telemetry_enabled` non fa piu parte del protocollo
- `invert_roll` e `invert_pitch` non fanno piu parte del protocollo
- i booleani del protocollo devono essere booleani JSON reali
- `imu_ready` e il campo live `ir` descrivono sempre e solo il sensore reale
- `gps_present` nello `status` descrive solo la presenza rilevata del modulo GNSS sulla UART, non la disponibilita di dati GPS nell'applicazione
- `gps_configured` indica se la configurazione runtime via UBX e stata confermata dal modulo
- `gps_streaming` indica se il firmware sta ricevendo frame `UBX-NAV-PVT` recenti
- `gps_usable` nello `status` indica se l'ultimo fix GPS e valido e usabile in quel momento
- `gok` nel `live` indica se i dati GPS del singolo campione sono validi e usabili lato app
- `gps_fix_type`, `gps_num_sv` e `gps_fix_age_ms` sono campi diagnostici tecnici di supporto
- se il GPS non e presente o non sta streamando, i campi GPS nel `live` restano comunque presenti ma valgono `0` o `false`
- in caso di fault I2C runtime, `imu_ready` / `ir` scendono a `false`, `i2c_address` torna a `0x00` e il firmware tenta automaticamente la reinizializzazione della IMU

## File di documentazione inclusi
- `doc/README.md` - panoramica generale
- `doc/ARCHITETTURA.md` - struttura tecnica e flusso dati
- `doc/BUILD.md` - build, flash e verifica
- `doc/HARDWARE.md` - cablaggio e montaggio
- `doc/protocol_examples.txt` - esempi rapidi del protocollo BLE
- `AGENTS.md` - guida operativa per modifiche future
