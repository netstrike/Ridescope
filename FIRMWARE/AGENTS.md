# AGENTS / CODEX - Istruzioni operative

## Obiettivo
Questo file serve a chi modifica il progetto in seguito per capire rapidamente:
- cosa e gia stato fatto
- cosa non va reintrodotto
- come toccare il codice senza rompere l'architettura

## Stato attuale del progetto
Il firmware e gia stato portato a questa architettura:
- logica di inclinazione IMU-first; il firmware puo configurare tecnicamente un GPS esterno e decodificare `UBX-NAV-PVT`; se il GPS e presente, streamma e ha un fix valido/fresco, puo usarne la velocita come ausilio nei calcoli dei filtri
- hardware target: ESP32-C6FH8 + breakout Adafruit LSM6DSOX
- modulo GNSS opzionale tipo `SAM-M10Q` su `GPIO19` RX da `TX GPS` e `GPIO20` TX verso `RX GPS`, configurato a runtime via UBX su `115200` e `10 Hz`
- LED RGB onboard usato come indicatore locale di stato firmware
- trasporto applicativo solo `Bluetooth LE`
- pairing obbligatorio con passkey statica
- telemetria live sempre `compact`, inviata su characteristic `notify`
- il payload `live` include ora anche un sottoinsieme compatto di dati GPS dal parser `UBX-NAV-PVT`; la pipeline filtri puo usare il GPS solo come ausilio opzionale quando `GpsAidData.valid` e true
- OTA solo via BLE a chunk, nessun supporto Wi-Fi / HTTP / WebSocket
- configurazione persistente in NVS
- mappa completa degli assi sensore persistente e configurabile via protocollo, con default `body_lateral_axis=+x`, `body_longitudinal_axis=+y`, `body_vertical_axis=+z`
- nessun parametro legacy di compatibilita per `peak_filter_enabled`, `peak_lpf_alpha`, `reset-peaks`, `invert_roll`, `invert_pitch` o selezione formato telemetria
- fault runtime I2C della IMU degradano `imu_ready` a `false` e attivano un recovery automatico periodico del sensore
- documentazione in italiano

## Decisioni architetturali gia prese
1. **GPS opzionale nei calcoli**
   Il firmware puo usare il GPS nei calcoli dei filtri solo quando il modulo e presente, lo stream `UBX-NAV-PVT` e fresco e il fix e valido/usabile.
   Quando il GPS manca, non e connesso, non streamma o il fix non e valido, la pipeline deve degradare automaticamente a IMU-only senza errori applicativi.
   Non sono ammessi logica di percorso, salvataggio tracce, mappe o UX GPS nel firmware: questi restano nell'app.

2. **Non programmare automaticamente OTP del GPS**
   Il `high CPU clock` del `SAM-M10Q` descritto dal manuale u-blox richiede una scrittura `OTP` permanente.
   Non va attivato di default dal firmware e non va programmato automaticamente senza una decisione esplicita e consapevole.

3. **Seriale solo come supporto tecnico**
   La seriale deve restare limitata ai log di eventi tecnici. Il canale principale per l'app e BLE.

4. **Niente protocollo applicativo su seriale**
   Nessun comando JSON o telemetria applicativa deve passare dalla seriale.

5. **Filtri singolarmente disattivabili**
   I filtri devono rimanere configurabili e disattivabili via protocollo.

6. **Documentazione sempre aggiornata**
   Ogni modifica al codice deve aggiornare anche la documentazione correlata.
   Ogni modifica al firmware deve essere registrata in `CHANGELOG.md` in forma testuale.
   Lo storico dettagliato delle righe modificate e dei rollback e gestito da Git.

7. **Non reintrodurre Wi-Fi**
   Wi-Fi, HTTP, WebSocket e SoftAP non devono rientrare nel firmware senza una decisione architetturale esplicita.

## File che vanno toccati in base al tipo di modifica

### Se modifichi i filtri
Aggiorna sempre:
- `src/filters.h`
- `src/filters.cpp`
- `src/types.h`
- `src/storage.cpp`
- `src/comm.cpp`
- `doc/README.md`
- `doc/ARCHITETTURA.md`
- `doc/protocol_examples.txt`

### Se modifichi la mappa assi sensore
Aggiorna sempre:
- `src/types.h`
- `src/axis_map.h`
- `src/axis_map.cpp`
- `src/filters.cpp`
- `src/storage.cpp`
- `src/comm_dispatch.cpp`
- `src/comm_protocol.cpp`
- `doc/README.md`
- `doc/ARCHITETTURA.md`
- `doc/protocol_examples.txt`
- `AGENTS.md` se cambia la logica generale

### Se modifichi la configurazione persistente
Aggiorna sempre:
- `src/types.h`
- `src/storage.h`
- `src/storage.cpp`
- `src/comm.cpp`
- `doc/README.md`
- `AGENTS.md` se cambia la logica generale

### Se modifichi il protocollo BLE
Aggiorna sempre:
- `src/comm.h`
- `src/comm.cpp`
- `src/comm_transport.h`
- `src/comm_transport.cpp`
- `src/messages.h`
- `src/messages.cpp`
- `doc/README.md`
- `doc/protocol_examples.txt`
- `doc/ARCHITETTURA.md` se cambia il flusso

### Se modifichi l'OTA
Aggiorna sempre:
- `src/comm.cpp`
- `src/comm_transport.cpp`
- `doc/BUILD.md`
- `doc/README.md`
- `platformio.ini` se cambiano dipendenze o partizioni

### Se modifichi hardware target o pin
Aggiorna sempre:
- `src/config.h`
- `doc/README.md`
- `doc/BUILD.md`
- `doc/ARCHITETTURA.md` se cambia il flusso sensore

### Se modifichi il GPS UART / SAM-M10Q
Aggiorna sempre:
- `src/config.h`
- `src/gps.h`
- `src/gps.cpp`
- `src/types.h`
- `src/comm_protocol.h`
- `src/comm_protocol.cpp`
- `src/messages.h`
- `src/messages.cpp`
- `src/inclinometro_esp32.ino`
- `doc/README.md`
- `doc/BUILD.md`
- `doc/HARDWARE.md`
- `doc/ARCHITETTURA.md`
- `doc/protocol_examples.txt`
- `AGENTS.md` se cambia il perimetro logico

## Regole pratiche da rispettare
- Non reintrodurre mappe o log di viaggio nel firmware.
- Il GPS puo essere parsato per stato tecnico, campi compact live e ausilio opzionale ai filtri quando il fix e valido/fresco.
- Il GPS puo essere esposto nel `live` solo nel sottoinsieme compact documentato e puo influenzare i calcoli solo attraverso dati validati come `GpsAidData`.
- I campi GPS nel `live` sono sempre presenti nello schema corrente; l'app deve usare `gok` nel `live` o `gps_usable` nello `status` per capire se il fix corrente e davvero usabile.
- Non aggiungere dipendenze pesanti se non strettamente necessarie.
- Mantieni il parser JSON coerente con il protocollo corrente.
- Mantieni testi di log ed errori centralizzati in `src/messages.h` e `src/messages.cpp`.
- Registra sempre ogni modifica firmware in `CHANGELOG.md` con descrizione testuale chiara di cosa e cambiato.
- Non reintrodurre fake telemetry o campi protocollo collegati come `debug_fake_telemetry_enabled` o `debug_fake_telemetry_active`.
- Non reintrodurre nei payload live/status i campi legacy `mrl`, `mrr`, `psr`, `maf`, `mdb`, `ag`, `at` senza versionare il protocollo e aggiornare app/documentazione.
- Non reintrodurre endpoint o campi legacy solo per compatibilita senza una necessita reale.
- Mantieni il frame canonico firmware: `X` laterale, `Y` longitudinale, `Z` verticale; la mappa assi deve solo rimappare il sensore in questo frame.
- Mantieni allineati UUID BLE, passkey e documentazione se cambiano.
- Mantieni il modello di trasporto attuale:
  una richiesta JSON per write su `command_rx`, risposte su `response_tx`, live su `live_tx`, chunk OTA binari su `ota_data_rx`.
- Le notifiche BLE che superano un singolo pacchetto restano spezzate e delimitate da newline.
- Se aggiungi un nuovo parametro configurabile, deve essere:
  - definito in `AppConfig`
  - persistito in NVS
  - esposto via protocollo
  - documentato
- Se aggiungi un nuovo campo di telemetria, documentalo negli esempi protocollo.
- Se cambi il comportamento del debug seriale, mantienilo confinato a log tecnici oppure documenta chiaramente il nuovo comportamento.

## Sequenza consigliata per modifiche importanti
1. aggiornare i tipi dati
2. aggiornare il codice funzionale
3. aggiornare la persistenza
4. aggiornare il protocollo
5. aggiornare la documentazione
6. aggiornare `CHANGELOG.md`
7. rigenerare lo zip del firmware

## Nota finale
Questo file non sostituisce il README in `doc/README.md`: serve come guida operativa per chi mette mano al codice dopo.
