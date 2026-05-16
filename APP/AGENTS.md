# AGENTS.md - RideScope

## Stato attuale
Base Android Studio vuota convertita in progetto Kotlin + Jetpack Compose per l'app RideScope.

## File chiave
- `app/src/main/java/com/example/ridescope/MainActivity.kt`
- `app/src/main/java/com/example/ridescope/ui/AppRoot.kt`
- `app/src/main/java/com/example/ridescope/ui/home/TelemetryDashboardWidget.kt`
- `app/src/main/java/com/example/ridescope/data/network/FirmwareHttpClient.kt`
- `app/src/main/java/com/example/ridescope/data/network/FirmwareWebSocketClient.kt`
- `app/src/main/java/com/example/ridescope/data/repository/TelemetryRepository.kt`
- `doc/README_APP_ANDROID.md`

## Regole progetto
- documentazione in italiano
- riferimento canonico firmware: `doc/HARDWARE.md` e `doc/README.md`
- non usare convenzioni assi diverse da quelle del firmware
- evitare di introdurre campi UI che il firmware non fornisce, salvo moduli esplicitamente lato app
- dopo modifiche all'app, aggiornare sempre il dispositivo fisico collegato usando `scripts/deploy-device.ps1`

## Firmware IMU
- Percorso: D:\PROGETTI\IMU_MOTO\FIRMWARE
- 
