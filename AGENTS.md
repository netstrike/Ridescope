# AGENTS.md - RideScope

## Scopo
Questo file contiene le direttive operative per chi modifica il repository RideScope.
Le istruzioni qui valgono per tutto il repository; i file `APP/AGENTS.md` e
`FIRMWARE/AGENTS.md` aggiungono regole specifiche per i rispettivi sottoprogetti.

## Repository e versionamento
- Il repository Git canonico e `https://github.com/netstrike/Ridescope.git`.
- Il branch operativo principale e `main`.
- Ogni modifica completata deve essere committata e pushata su GitHub.
- Prima di lavorare eseguire sempre `git status --short --branch`.
- Prima di modifiche non banali verificare di essere allineati con `origin/main`.
- A fine lavoro il working tree deve essere pulito e `main` deve tracciare `origin/main`.
- Non usare `git reset --hard`, `git checkout --` o comandi distruttivi senza richiesta esplicita.
- Non forzare il push (`--force` / `--force-with-lease`) senza richiesta esplicita.
- Se il push viene rifiutato per commit remoti, fare `git fetch`, integrare i cambiamenti e risolvere i conflitti senza perdere contenuto locale o remoto.

Flusso standard:

```powershell
git status --short --branch
git add <file>
git commit -m "Descrizione breve"
git push
```

## Struttura progetto
- `APP/` contiene l'app Android Kotlin / Jetpack Compose.
- `FIRMWARE/` contiene il firmware ESP32-C6 PlatformIO / Arduino.
- `WEB/` contiene lo script PHP per upload debug.
- `README.md` alla root descrive la struttura e le variabili ambiente principali.

## Sicurezza e segreti
- Non salvare credenziali, token, password FTP, password di upload o chiavi API nel repository.
- Usare variabili ambiente per credenziali e configurazioni locali sensibili.
- Non committare `local.properties`, `.env`, cache IDE, output build, APK, firmware binari o cartelle generate.
- Se compare un segreto nei file versionati, fermarsi, rimuoverlo e avvisare che la credenziale va ruotata.

Variabili ambiente note:
- App: `RIDESCOPE_APP_FTP_USER`, `RIDESCOPE_APP_FTP_PASSWORD`, `RIDESCOPE_APP_FTP_HOST`, `RIDESCOPE_APP_FTP_PORT`, `RIDESCOPE_APP_FTP_DIRECTORY`
- Firmware: `RIDESCOPE_FTP_USER`, `RIDESCOPE_FTP_PASSWORD`, `RIDESCOPE_SKIP_FTP_PUBLISH`
- Web: `RIDESCOPE_UPLOAD_PASSWORD`

## Regole generali di modifica
- Mantenere la documentazione in italiano.
- Preferire modifiche piccole, tracciabili e coerenti con lo stile esistente.
- Aggiornare la documentazione quando cambia comportamento, configurazione, protocollo, build o workflow utente.
- Non introdurre dipendenze nuove senza una necessita chiara.
- Non modificare file generati o artefatti di build come sorgente canonica.
- Rispettare `.gitignore` e `.gitattributes`.

## App Android
- Seguire le direttive in `APP/AGENTS.md`.
- Entry point principali:
  - `APP/app/src/main/java/com/example/ridescope/MainActivity.kt`
  - `APP/app/src/main/java/com/example/ridescope/ui/AppRoot.kt`
  - `APP/app/src/main/java/com/example/ridescope/data/network/FirmwareBleClient.kt`
  - `APP/app/src/main/java/com/example/ridescope/data/repository/TelemetryRepository.kt`
- Il firmware canonico usa BLE GATT custom; non reintrodurre client HTTP/WebSocket per il firmware senza decisione esplicita.
- Le convenzioni assi e i campi protocollo devono rimanere allineati al firmware.
- Se cambi UI o workflow app, aggiornare `APP/doc/README_APP_ANDROID.md` quando serve.
- Per build/deploy su device usare gli script esistenti, evitando di committare output generati.

## Firmware
- Seguire sempre anche `FIRMWARE/AGENTS.md`.
- Entry point principale: `FIRMWARE/src/inclinometro_esp32.ino`.
- Documentazione firmware canonica:
  - `FIRMWARE/doc/README.md`
  - `FIRMWARE/doc/BUILD.md`
  - `FIRMWARE/doc/HARDWARE.md`
  - `FIRMWARE/doc/ARCHITETTURA.md`
  - `FIRMWARE/doc/protocol_examples.txt`
- Ogni modifica firmware deve aggiornare `FIRMWARE/CHANGELOG.md`.
- Non reintrodurre Wi-Fi, HTTP, WebSocket, SoftAP o logica applicativa GPS nel firmware senza decisione esplicita.
- Il firmware resta responsabile di IMU, calibrazione, filtri, ausilio GPS opzionale con fix valido/fresco, BLE e OTA BLE.

## Web
- `WEB/ridescope.php` deve usare `RIDESCOPE_UPLOAD_PASSWORD`; non inserire password hardcoded.
- Validare sempre upload, nomi file e directory di destinazione.
- Aggiornare `WEB/ridescope.README.md` se cambia il contratto HTTP o la configurazione.

## Verifica
- Scegliere la verifica in base al rischio della modifica.
- Per app Android, preferire test Gradle o build debug quando si tocca codice Kotlin, manifest, risorse o Gradle.
- Per firmware, preferire build PlatformIO quando si tocca codice C++/Arduino, `platformio.ini` o partizioni.
- Se non e possibile eseguire test o build, dichiararlo nel riepilogo finale e spiegare il motivo.

## Chiusura lavoro
Prima di concludere:
- controllare `git status --short --branch`
- verificare che non siano staged o unstaged file indesiderati
- committare con messaggio chiaro
- pushare su `origin/main`
- riportare commit finale e verifiche eseguite
