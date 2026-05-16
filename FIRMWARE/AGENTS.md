# AGENTS / CODEX - Istruzioni operative

## Obiettivo
Questo file serve a chi modifica il progetto in seguito per capire rapidamente:
- cosa e gia stato fatto
- cosa non va reintrodotto
- come toccare il codice senza rompere l'architettura

## Stato attuale del progetto
Il firmware e gia stato portato a questa architettura:
- solo logica di inclinazione; il firmware puo configurare tecnicamente un GPS esterno e decodificare `UBX-NAV-PVT` per sola diagnostica tecnica, ma non deve usarne i dati nella logica applicativa
- hardware target: ESP32-C6FH8 + breakout Adafruit LSM6DSOX
- modulo GNSS opzionale tipo `SAM-M10Q` su `GPIO19` RX da `TX GPS` e `GPIO20` TX verso `RX GPS`, configurato a runtime via UBX su `115200` e `10 Hz`
- LED RGB onboard usato come indicatore locale di stato firmware
- trasporto applicativo solo `Bluetooth LE`
- pairing obbligatorio con passkey statica
- telemetria live sempre `compact`, inviata su characteristic `notify`
- il payload `live` include ora anche un sottoinsieme compatto di dati GPS dal parser `UBX-NAV-PVT`, senza introdurre logica di percorso o fusioni GPS nel firmware
- OTA solo via BLE a chunk, nessun supporto Wi-Fi / HTTP / WebSocket
- configurazione persistente in NVS
- mappa completa degli assi sensore persistente e configurabile via protocollo, con default `body_lateral_axis=+x`, `body_longitudinal_axis=+y`, `body_vertical_axis=+z`
- nessun parametro legacy di compatibilita per `peak_filter_enabled`, `peak_lpf_alpha`, `reset-peaks`, `invert_roll`, `invert_pitch` o selezione formato telemetria
- fault runtime I2C della IMU degradano `imu_ready` a `false` e attivano un recovery automatico periodico del sensore
- documentazione in italiano

## Decisioni architetturali gia prese
1. **Niente logica GPS nel firmware**
   Il firmware non deve calcolare nulla usando GPS e non deve gestire dati di percorso.
   E consentita la configurazione tecnica del ricevitore, il parsing tecnico minimo di `UBX-NAV-PVT`, l'esposizione di stato diagnostico su `status` e la pubblicazione dei campi GPS compact nel `live`.
   Non sono ammessi uso del GPS nei filtri di inclinazione, logica di percorso, salvataggio tracce o telemetria GPS applicativa oltre i campi compact documentati.

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
   Da adesso in poi ogni modifica al firmware deve essere registrata anche in `CHANGELOG.md` in forma testuale e in `CHANGES.md` in stile GIT/diff per mantenere lo storico e facilitare un eventuale rollback.
   In `CHANGES.md` non basta elencare file toccati o descrivere la modifica:
   bisogna riportare le righe realmente modificate in formato diff, con blocchi per file e righe prefissate da `-` e `+`.
   In `CHANGES.md` vanno riportati solo file di codice del firmware; non inserire diff di file documentali come `doc/*`, `AGENTS.md`, `CHANGELOG.md` o `CHANGES.md`.

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
- Non reintrodurre GPS, velocita, mappe o log di viaggio nel firmware.
- Il GPS puo essere parsato solo per stato tecnico e diagnostica minima; non usarlo nei calcoli di roll/pitch o nel payload `live` senza una nuova decisione architetturale.
- Il GPS puo essere esposto nel `live` solo nel sottoinsieme compact documentato; non usarlo comunque nei calcoli di roll/pitch o per introdurre logiche di percorso nel firmware.
- I campi GPS nel `live` sono sempre presenti nello schema corrente; l'app deve usare `gok` nel `live` o `gps_usable` nello `status` per capire se il fix corrente e davvero usabile.
- Non aggiungere dipendenze pesanti se non strettamente necessarie.
- Mantieni il parser JSON coerente con il protocollo corrente.
- Mantieni testi di log ed errori centralizzati in `src/messages.h` e `src/messages.cpp`.
- Registra sempre ogni modifica firmware in `CHANGELOG.md` con descrizione testuale chiara di cosa e cambiato.
- Aggiorna sempre `CHANGES.md` con le modifiche ai file in stile GIT/diff, cosi da mantenere una traccia utile per eventuale rollback.
- In `CHANGES.md` inserisci veri blocchi diff con righe di codice modificate.
- Non sono accettati in `CHANGES.md` riepiloghi testuali, elenchi di file o descrizioni senza le righe effettivamente cambiate.
- In `CHANGES.md` inserisci solo diff di file di codice firmware, non diff di documentazione o file di processo.
- File da escludere sempre da `CHANGES.md`: `doc/*`, `AGENTS.md`, `CHANGELOG.md`, `CHANGES.md`, file di build, manifest o altri file di processo non appartenenti al codice firmware.
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
6. aggiornare `CHANGELOG.md` e `CHANGES.md`
7. rigenerare lo zip del firmware

## Nota finale
Questo file non sostituisce il README in `doc/README.md`: serve come guida operativa per chi mette mano al codice dopo.
