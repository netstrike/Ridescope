# RideScope

RideScope e un sistema per telemetria moto basato su:

- app Android Kotlin / Jetpack Compose in `APP`
- firmware ESP32-C6 PlatformIO / Arduino in `FIRMWARE`
- endpoint PHP di upload debug in `WEB`

## Struttura

- `APP/` - applicazione Android, UI telemetria, BLE, registrazioni, export e aggiornamenti OTA
- `FIRMWARE/` - firmware IMU/GPS/BLE per ESP32-C6
- `WEB/` - script PHP per ricevere file diagnostici

## Documentazione

- App Android: `APP/doc/README_APP_ANDROID.md`
- Firmware: `FIRMWARE/doc/README.md`
- Build firmware: `FIRMWARE/doc/BUILD.md`
- Hardware firmware: `FIRMWARE/doc/HARDWARE.md`
- Upload debug web: `WEB/ridescope.README.md`

## Build GitHub

- Firmware: `.github/workflows/firmware.yml` compila PlatformIO su GitHub Actions e pubblica `firmware.bin` / `manifest.json` come artifact.
- Tag firmware `firmware-*`: la workflow crea una GitHub Release con `firmware.bin` e `manifest.json` allegati.
- GitHub Pages firmware: `https://netstrike.github.io/Ridescope/firmware/manifest.json` e `https://netstrike.github.io/Ridescope/firmware/firmware.bin`.

## Configurazione locale

Le credenziali non devono essere salvate nel repository.

Variabili ambiente app:

- `RIDESCOPE_APP_FTP_USER`
- `RIDESCOPE_APP_FTP_PASSWORD`
- `RIDESCOPE_APP_FTP_HOST`
- `RIDESCOPE_APP_FTP_PORT`
- `RIDESCOPE_APP_FTP_DIRECTORY`

Variabili ambiente firmware:

- `RIDESCOPE_FTP_USER`
- `RIDESCOPE_FTP_PASSWORD`
- `RIDESCOPE_SKIP_FTP_PUBLISH=1`

Variabili ambiente web:

- `RIDESCOPE_UPLOAD_PASSWORD`
