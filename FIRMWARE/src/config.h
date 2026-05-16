// Parametri statici del progetto.
// Qui si raccolgono i profili hardware e i valori di default che non devono stare in NVS.

#pragma once

#include <Arduino.h>

#if ARDUINO_USB_CDC_ON_BOOT
  #if ARDUINO_USB_MODE
    #include <HWCDC.h>
    #define TECHNICAL_SERIAL HWCDCSerial
  #else
    #include <USBCDC.h>
    #define TECHNICAL_SERIAL USBSerial
  #endif
#else
  #define TECHNICAL_SERIAL Serial
#endif

static constexpr const char* BOARD_PROFILE = "Waveshare ESP32-C6 Mini Development Board";
static constexpr const char* MCU_PROFILE = "ESP32-C6FH8";
static constexpr const char* PARTITION_PROFILE = "custom_ota_8mb";
static constexpr const char* SENSOR_BOARD_PROFILE = "Adafruit LSM6DSOX";
static constexpr const char* SENSOR_CHIP_PROFILE = "LSM6DSOX";

#ifndef AUTO_FIRMWARE_BUILD
  #define AUTO_FIRMWARE_BUILD "0.0.0"
#endif

#ifndef AUTO_FIRMWARE_TIMESTAMP
  #define AUTO_FIRMWARE_TIMESTAMP "unknown"
#endif

#ifndef AUTO_FIRMWARE_PROTOCOL
  #define AUTO_FIRMWARE_PROTOCOL "0.0"
#endif

// I metadati firmware vengono iniettati dalla build PlatformIO.
// Se il build script non gira, restano i fallback sopra.
static constexpr const char* FIRMWARE_BUILD = AUTO_FIRMWARE_BUILD;
static constexpr const char* FIRMWARE_TIMESTAMP = AUTO_FIRMWARE_TIMESTAMP;
static constexpr const char* FIRMWARE_PROTOCOL = AUTO_FIRMWARE_PROTOCOL;

// Parametri generali di esecuzione.
static constexpr uint32_t SERIAL_BAUDRATE = 115200;
static constexpr uint32_t LOOP_INTERVAL_MS = 10;
static constexpr const char* NVS_NAMESPACE = "inclino";
static constexpr const char* BLE_DEVICE_NAME = "RideScope";
static constexpr const char* BLE_SERVICE_UUID = "58f2a0f5-3d5e-4697-b3c9-67dbe7f5a501";
static constexpr const char* BLE_COMMAND_RX_UUID = "58f2a0f5-3d5e-4697-b3c9-67dbe7f5a502";
static constexpr const char* BLE_RESPONSE_TX_UUID = "58f2a0f5-3d5e-4697-b3c9-67dbe7f5a503";
static constexpr const char* BLE_LIVE_TX_UUID = "58f2a0f5-3d5e-4697-b3c9-67dbe7f5a504";
static constexpr const char* BLE_OTA_DATA_RX_UUID = "58f2a0f5-3d5e-4697-b3c9-67dbe7f5a505";
static constexpr uint32_t BLE_STATIC_PASSKEY = 123456;
static constexpr uint16_t BLE_PREFERRED_MTU = 247;
static constexpr uint32_t BLE_OTA_REBOOT_DELAY_MS = 1500;

// LED RGB onboard della Waveshare ESP32-C6-Zero.
// Il core Arduino della variante Waveshare lo collega a GPIO8 con ordine colori RGB.
static constexpr bool STATUS_LED_ENABLED = true;
static constexpr uint8_t STATUS_LED_PIN = 8;
static constexpr uint8_t STATUS_LED_BRIGHTNESS = 24;

// UART dedicata opzionale per un modulo GNSS esterno tipo SAM-M10Q.
// Il firmware prova il bootstrap sul baudrate factory tipico del modulo
// e poi lo riconfigura a runtime via UBX in sola RAM, con parser NAV-PVT.
static constexpr bool GPS_UART_ENABLED = true;
static constexpr int GPS_UART_RX_PIN = 19; // ESP32 RX <- TX del GPS
static constexpr int GPS_UART_TX_PIN = 20; // ESP32 TX -> RX del GPS
static constexpr uint32_t GPS_UART_FACTORY_BAUDRATE = 38400;
static constexpr uint32_t GPS_UART_LEGACY_FALLBACK_BAUDRATE = 9600;
static constexpr uint32_t GPS_UART_BAUDRATE = 115200;
static constexpr uint16_t GPS_NAV_MEAS_RATE_MS = 100; // 10 Hz
static constexpr uint8_t GPS_NAV_PVT_RATE = 1;
static constexpr uint8_t GPS_NAV_DYNAMIC_MODEL = 4; // AUTOMOT
static constexpr uint32_t GPS_FIX_STALE_TIMEOUT_MS = 1500;
// Il manuale SAM-M10Q descrive il "high CPU clock" solo come scrittura OTP permanente.
// Tenerlo disabilitato evita modifiche irreversibili del modulo da firmware.
static constexpr bool GPS_SAM_M10Q_PROGRAM_HIGH_CPU_CLOCK_OTP = false;

// ESP32-C6 usa la GPIO matrix: i pin I2C possono essere rimappati se necessario.
// I valori sotto sono i default del progetto e vanno adeguati al cablaggio reale del sensore.
static constexpr int I2C_SDA_PIN = 21;
static constexpr int I2C_SCL_PIN = 22;
static constexpr uint32_t I2C_CLOCK_HZ = 400000;

// Supporto opzionale interrupt data-ready dal breakout Adafruit.
// Imposta il pin GPIO collegato a INT1, oppure lascia -1 per usare il polling puro.
static constexpr int LSM6DSOX_INT1_PIN = -1;

static constexpr uint8_t LSM6DSOX_I2C_ADDR_LOW = 0x6A;
static constexpr uint8_t LSM6DSOX_I2C_ADDR_HIGH = 0x6B;
static constexpr uint8_t LSM6DSOX_WHO_AM_I = 0x6C;
