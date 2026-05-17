# Collegamenti hardware tra ESP32-C6-Zero, breakout Adafruit LSM6DSOX e GPS UART opzionale

Questo documento descrive i collegamenti da effettuare tra:

- scheda MCU: Waveshare `ESP32-C6-Zero` / `ESP32-C6 Mini Development Board`
- sensore: Adafruit `LSM6DSOX - STEMMA QT / Qwiic`
- modulo opzionale: ricevitore GNSS UART tipo `SAM-M10Q`

I nomi pin sotto sono riportati come serigrafati sui PCB e sono coerenti con i pin attualmente usati dal firmware in `src/config.h`.

Nota:
- la scheda usa anche il LED RGB onboard su `GPIO8` come indicatore locale di stato firmware
- questo pin non fa parte del cablaggio IMU e va lasciato libero

## Collegamenti minimi richiesti

| Funzione | Pin su Waveshare ESP32-C6-Zero | Pin su breakout Adafruit LSM6DSOX | Obbligatorio | Note |
|---|---|---|---|---|
| Alimentazione sensore | `3V3(OUT)` | `Vin` | Si | Collegamento consigliato per logica a 3.3V |
| Massa comune | `GND` | `GND` | Si | Massa in comune obbligatoria |
| I2C SDA | `GP21` | `SDA` | Si | Corrisponde a `I2C_SDA_PIN = 21` in `src/config.h` |
| I2C SCL | `GP22` | `SCL` | Si | Corrisponde a `I2C_SCL_PIN = 22` in `src/config.h` |

## Collegamento opzionale interrupt data-ready

| Funzione | Pin su Waveshare ESP32-C6-Zero | Pin su breakout Adafruit LSM6DSOX | Obbligatorio | Note |
|---|---|---|---|---|
| Data ready IMU | un GPIO libero serigrafato `GPx` | `INT1` | No | Richiede modifica di `LSM6DSOX_INT1_PIN` in `src/config.h` |

Stato attuale del firmware:

- `LSM6DSOX_INT1_PIN = -1`
- quindi il firmware usa il polling e `INT1` puo restare scollegato

## Collegamento opzionale GPS UART

| Funzione | Pin su Waveshare ESP32-C6-Zero | Pin sul GPS UART | Obbligatorio | Note |
|---|---|---|---|---|
| RX ESP32 dal GPS | `GP19` | `TX` | No | Corrisponde a `GPS_UART_RX_PIN = 19` |
| TX ESP32 verso GPS | `GP20` | `RX` | No | Corrisponde a `GPS_UART_TX_PIN = 20` |
| Alimentazione GPS | `3V3(OUT)` | `VCC` / `VIN` | No | Verificare il pin di alimentazione del modulo specifico |
| Massa comune | `GND` | `GND` | No | Obbligatoria se il GPS e collegato |

## Cablaggio consigliato

```text
ESP32-C6-Zero        Adafruit LSM6DSOX breakout
------------------------------------------------
3V3(OUT)        ->   Vin
GND             ->   GND
GP21            ->   SDA
GP22            ->   SCL
(opz.) GPx      ->   INT1

ESP32-C6-Zero        Modulo GPS UART
------------------------------------
GP19            ->   TX
GP20            ->   RX
3V3(OUT)        ->   VCC / VIN
GND             ->   GND
```

## Note pratiche

- Non collegare `3V3(OUT)` del micro al pin `3Vo` del breakout Adafruit: `3Vo` e un'uscita del regolatore del breakout, non l'ingresso di alimentazione.
- Il breakout Adafruit accetta alimentazione su `Vin`; con questo progetto la scelta naturale resta `3V3(OUT) -> Vin`.
- Il firmware usa il solo sensore `LSM6DSOX` via I2C. I pin opzionali di interrupt o configurazione indirizzo non sono necessari per il funzionamento base.
- Se colleghi un GPS UART esterno, il firmware prova il bootstrap del `SAM-M10Q` sul baudrate factory tipico, lo riconfigura poi a `115200` con output `UBX-NAV-PVT` a `10 Hz` e ne decodifica i frame per diagnostica, telemetria compact e ausilio opzionale ai filtri quando il fix e valido/fresco.
- Il firmware non programma automaticamente il `high CPU clock` OTP del modulo, perche sarebbe una modifica permanente del ricevitore.
- L'indirizzo I2C atteso dal firmware per la IMU e `0x6A` oppure `0x6B`. Il breakout Adafruit usa di default `0x6A` per accel/gyro; `ADAG` serve solo se vuoi cambiare indirizzo.

## Riferimento orientamento sensore

Convenzione adottata nel firmware:

- asse `Y`: longitudinale moto, positivo verso anteriore
- asse `X`: trasversale moto, positivo verso destra
- asse `Z`: verticale moto, positivo verso l'alto

Questa convenzione non cambia i collegamenti elettrici, ma e importante per montare il breakout con il verso corretto.

## Se vuoi usare un GPIO diverso per I2C o INT1

Il progetto puo essere adattato modificando:

- `I2C_SDA_PIN` in `src/config.h`
- `I2C_SCL_PIN` in `src/config.h`
- `LSM6DSOX_INT1_PIN` in `src/config.h`
- `GPS_UART_RX_PIN` in `src/config.h`
- `GPS_UART_TX_PIN` in `src/config.h`
- `GPS_UART_BAUDRATE` in `src/config.h`

Se cambi questi pin, aggiorna anche:

- `doc/README.md`
- `doc/BUILD.md`
- `doc/ARCHITETTURA.md` se cambia il flusso sensore
