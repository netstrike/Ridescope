# BUILD

Questo documento copre build, flash e verifica firmware.
Per cablaggio, pinout e montaggio fisico vedi `doc/HARDWARE.md`.

## File rilevanti per la build
- sketch principale: `src/inclinometro_esp32.ino`
- configurazione hardware e BLE: `src/config.h`
- partizioni OTA: `extras/partitions_8mb_ota.csv`
- configurazione PlatformIO: `platformio.ini`

## Arduino IDE
Impostazioni consigliate:
1. installare il package `esp32` di Espressif in versione `3.3.5` o superiore
2. selezionare la board `Waveshare ESP32-C6-Zero`
3. abilitare `Tools > USB CDC On Boot`
4. verificare flash da 8 MB, se disponibile nel menu
5. usare una partizione compatibile con OTA
6. monitor seriale a `115200`

Se l'ambiente Arduino IDE non permette di usare con chiarezza il CSV di partizioni personalizzato, conviene usare PlatformIO.

## PlatformIO
Nel progetto e gia presente `platformio.ini` pronto all'uso.

Punti importanti:
- platform ESP32: `pioarduino/platform-espressif32`
- board: `esp32-c6-devkitc-1`
- framework: `arduino`
- `build_flags` include `-DARDUINO_USB_MODE=1` e `-DARDUINO_USB_CDC_ON_BOOT=1`
- `extra_scripts = pre:extras/pio_toolchain_fix.py, pre:extras/pio_build_metadata.py`
- `custom_firmware_version = 4.5.0`
- `custom_publish_enabled = no`
- `custom_publish_ftp_url = ftp://ftp.sparvieri.org/www.sparvieri.org/ridescope/firmware`
- `custom_publish_ftps_enabled = yes`
- `custom_publish_ftps_verify_cert = no`
- `upload_flags = --no-stub`
- `board_build.flash_size = 8MB`
- `board_build.partitions = extras/partitions_8mb_ota.csv`
- dipendenza esplicita esterna: `bblanchon/ArduinoJson`
- il supporto BLE arriva dal framework Arduino ESP32, non richiede librerie aggiuntive in `lib_deps`

## Metadata build e manifest
Con build PlatformIO il firmware riceve automaticamente:
- `firmware.build` con formato semver incrementale, per esempio `4.4.1`
- `firmware.timestamp` con formato `YYYYMMDD.HHMMSS`
- `firmware.protocol` con la versione del protocollo BLE/JSON

Alla fine di ogni build viene generato anche:
- `.pio/build/<env>/manifest.json`

Il manifest contiene gli stessi campi esposti da `get_info` per il controllo OTA lato app.
La patch di `firmware.build` viene incrementata automaticamente a ogni build riuscita.
`custom_firmware_version` definisce la base semver della release line corrente.

Ordine di priorita per il prossimo numero build:
- ultimo `manifest.json` pubblicato sul repository remoto
- fallback locale `.pio/build/build_version_state.json`
- base configurata in `custom_firmware_version`

## Pubblicazione FTPS automatica
Con build PlatformIO riuscita il progetto pubblica automaticamente:
- `.pio/build/<env>/firmware.bin`
- `.pio/build/<env>/manifest.json`

Repository remoto corrente:
- `ftp://ftp.sparvieri.org/www.sparvieri.org/ridescope/firmware`

Ordine di pubblicazione:
- prima `firmware.bin`
- poi `manifest.json`

In questo modo il manifest nuovo compare solo quando il binario e gia disponibile.

Configurazione:
- `custom_publish_ftp_user` e `custom_publish_ftp_password` non devono contenere credenziali nel repository
- `custom_publish_ftps_enabled = yes` abilita FTPS esplicito su porta 21
- `custom_publish_ftps_verify_cert = no` disabilita la verifica del certificato server se la chain non e nel trust store locale
- le variabili ambiente `RIDESCOPE_FTP_USER` e `RIDESCOPE_FTP_PASSWORD` hanno precedenza
- `RIDESCOPE_SKIP_FTP_PUBLISH=1` permette di saltare la pubblicazione per build locali offline

## Perche serve la partition table custom
Il firmware usa OTA con:
- NVS separata
- partizione `otadata`
- due slot applicativi OTA

Questo permette di preservare dopo gli aggiornamenti:
- calibrazione sensore
- zero di riferimento
- configurazione filtri
- mappa assi sensore
- `telemetry_period_ms`
- `debug_serial`

## Checklist prima del primo flash
- controllare che i pin configurati in `src/config.h` siano coerenti con il cablaggio reale
- verificare cablaggio e orientamento del sensore in `doc/HARDWARE.md`
- se usi un GPS UART esterno, verificare `GPS_UART_RX_PIN = 19`, `GPS_UART_TX_PIN = 20`
- il firmware prova il bootstrap del `SAM-M10Q` sul baudrate factory `38400` e lo porta poi a `115200`
- decidere se usare polling o `INT1`
- verificare che l'app sappia usare BLE GATT con pairing a passkey statica

## Checklist dopo il primo flash
- advertising BLE visibile come `RideScope`
- pairing BLE funzionante con passkey `123456`
- `get_info`, `get_status` e telemetria `live_tx` funzionanti
- se il GPS UART e collegato, devono comparire i log tecnici di configurazione del `SAM-M10Q` e del rilevamento stream `UBX-NAV-PVT`
- con GPS collegato, `get_status` deve poter mostrare `gps_present`, `gps_configured` e poi `gps_streaming`
- se la IMU reale e collegata, `imu_ready = true`
- `i2c_address` coerente con `0x6a` o `0x6b`
- se durante una prova stacchi la IMU, `imu_ready` deve scendere a `false`, `i2c_address` tornare a `0x00` e il firmware deve tentare il recovery automatico quando il sensore viene ricollegato
- LED onboard verde lampeggiante quando BLE e IMU reale sono sani
- LED onboard rosso lampeggiante se la IMU reale manca o BLE non parte
- LED onboard blu lampeggiante durante OTA
- calibrazione salvata dopo reboot
- zero salvato dopo reboot
- OTA BLE funzionante con `ota_begin` -> chunk binari -> `ota_end`

Nota:
- il firmware non programma automaticamente il `high CPU clock` OTP del `SAM-M10Q`, perche il manuale u-blox lo dichiara permanente e irreversibile

## Recovery
Anche se l'OTA BLE e attivo, durante lo sviluppo va sempre mantenuto un percorso di recovery via USB.
