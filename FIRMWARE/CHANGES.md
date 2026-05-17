# Changes

## 2026-03-29 - Timestamp IMU, temperatura IMU e live compact 4.6
```diff
diff --git a/src/types.h b/src/types.h
@@ struct RawImuData @@
-    float gyroZDegS = 0.0f;
+    float gyroZDegS = 0.0f;
+    float temperatureC = 0.0f;
+    uint32_t sensorTimestampTicks = 0;
+    float sensorTimestampTickSeconds = 0.000025f;
+    bool sensorTimestampValid = false;
@@ struct RuntimeState @@
-    uint32_t timestampMs = 0;
+    uint32_t timestampMs = 0;
+    uint32_t imuTimestampTicks = 0;
     float rollDeg = 0.0f;
     float pitchDeg = 0.0f;
     float rawRollDeg = 0.0f;
     float rawPitchDeg = 0.0f;
     float longitudinalAccelG = 0.0f;
+    float imuTemperatureC = 0.0f;
     float sampleRateHz = 0.0f;
     bool imuReady = false;

diff --git a/src/axis_map.cpp b/src/axis_map.cpp
@@ RawImuData mapToBodyFrame @@
         bodyFrame.gyroXDegS = selectAxis(axisMap.bodyLateralAxis, sensorFrame.gyroXDegS, sensorFrame.gyroYDegS, sensorFrame.gyroZDegS);
         bodyFrame.gyroYDegS = selectAxis(axisMap.bodyLongitudinalAxis, sensorFrame.gyroXDegS, sensorFrame.gyroYDegS, sensorFrame.gyroZDegS);
         bodyFrame.gyroZDegS = selectAxis(axisMap.bodyVerticalAxis, sensorFrame.gyroXDegS, sensorFrame.gyroYDegS, sensorFrame.gyroZDegS);
+        bodyFrame.temperatureC = sensorFrame.temperatureC;
+        bodyFrame.sensorTimestampTicks = sensorFrame.sensorTimestampTicks;
+        bodyFrame.sensorTimestampTickSeconds = sensorFrame.sensorTimestampTickSeconds;
+        bodyFrame.sensorTimestampValid = sensorFrame.sensorTimestampValid;
         return bodyFrame;

diff --git a/src/sensors.h b/src/sensors.h
@@
-// Questo modulo nasconde i dettagli del LSM6DSOX e fornisce dati fisici gia convertiti.
+// Questo modulo nasconde i dettagli del LSM6DSOX e fornisce dati fisici gia convertiti,
+// inclusi timestamp hardware e temperatura interna.

diff --git a/src/sensors.cpp b/src/sensors.cpp
@@ costanti registri @@
-    constexpr uint8_t REG_CTRL3_C = 0x12;
-    constexpr uint8_t REG_STATUS = 0x1E;
+    constexpr uint8_t REG_CTRL3_C = 0x12;
+    constexpr uint8_t REG_CTRL10_C = 0x19;
+    constexpr uint8_t REG_OUT_TEMP_L = 0x20;
+    constexpr uint8_t REG_STATUS = 0x1E;
     constexpr uint8_t REG_OUTX_L_G = 0x22;
     constexpr uint8_t REG_OUTX_L_A = 0x28;
+    constexpr uint8_t REG_TIMESTAMP0 = 0x40;
+    constexpr uint8_t REG_TIMESTAMP2 = 0x42;
@@ costanti conversione @@
-    constexpr float ACC_SENSITIVITY_G_PER_LSB = 0.000061f;
-    constexpr float GYRO_SENSITIVITY_DPS_PER_LSB = 0.00875f;
+    constexpr float ACC_SENSITIVITY_G_PER_LSB = 0.000061f;
+    constexpr float GYRO_SENSITIVITY_DPS_PER_LSB = 0.00875f;
+    constexpr float TEMP_SENSITIVITY_LSB_PER_C = 256.0f;
+    constexpr float TEMP_OFFSET_C = 25.0f;
+    constexpr float SENSOR_TIMESTAMP_TICK_S = 0.000025f;
@@ helper timestamp @@
+    uint32_t makeUInt32(uint8_t byte0, uint8_t byte1, uint8_t byte2, uint8_t byte3)
+    {
+        return (static_cast<uint32_t>(byte3) << 24U) |
+               (static_cast<uint32_t>(byte2) << 16U) |
+               (static_cast<uint32_t>(byte1) << 8U) |
+               static_cast<uint32_t>(byte0);
+    }
+
+    bool configureTimestamping()
+    {
+        uint8_t ctrl10Value = 0;
+        if (!readRegisters(REG_CTRL10_C, &ctrl10Value, 1))
+        {
+            return false;
+        }
+
+        ctrl10Value |= CTRL10_C_TIMESTAMP_EN;
+        if (!writeRegister(REG_CTRL10_C, ctrl10Value))
+        {
+            return false;
+        }
+
+        return writeRegister(REG_TIMESTAMP2, TIMESTAMP_RESET_VALUE);
+    }
@@ tryInitializeSensor @@
         if (!writeRegister(REG_CTRL2_G, 0x40))
         {
             markSensorUnavailable(false);
             if (emitFailureLog)
             {
                 Debug::info(Messages::Log::kCtrl2WriteFailed);
             }
             return false;
         }
+
+        if (!configureTimestamping())
+        {
+            markSensorUnavailable(false);
+            if (emitFailureLog)
+            {
+                Debug::info(Messages::Log::kTimestampInitFailed);
+            }
+            return false;
+        }
@@ readImuRawNoCalibration @@
-        uint8_t gyroRaw[6] = {0};
-        uint8_t accelRaw[6] = {0};
+        uint8_t tempRaw[2] = {0};
+        uint8_t gyroRaw[6] = {0};
+        uint8_t accelRaw[6] = {0};
+        uint8_t timestampRaw[4] = {0};
 
-        if (!readRegisters(REG_OUTX_L_G, gyroRaw, sizeof(gyroRaw)) ||
-            !readRegisters(REG_OUTX_L_A, accelRaw, sizeof(accelRaw)))
+        if (!readRegisters(REG_OUT_TEMP_L, tempRaw, sizeof(tempRaw)) ||
+            !readRegisters(REG_OUTX_L_G, gyroRaw, sizeof(gyroRaw)) ||
+            !readRegisters(REG_OUTX_L_A, accelRaw, sizeof(accelRaw)) ||
+            !readRegisters(REG_TIMESTAMP0, timestampRaw, sizeof(timestampRaw)))
         {
             imuData = {};
             imuData.accelZ = 1.0f;
             return false;
         }
 
+        const int16_t temp = makeInt16(tempRaw[0], tempRaw[1]);
         const int16_t gx = makeInt16(gyroRaw[0], gyroRaw[1]);
         const int16_t gy = makeInt16(gyroRaw[2], gyroRaw[3]);
         const int16_t gz = makeInt16(gyroRaw[4], gyroRaw[5]);
@@ conversione fisica @@
         imuData.accelZ = az * ACC_SENSITIVITY_G_PER_LSB;
         imuData.gyroXDegS = gx * GYRO_SENSITIVITY_DPS_PER_LSB;
         imuData.gyroYDegS = gy * GYRO_SENSITIVITY_DPS_PER_LSB;
         imuData.gyroZDegS = gz * GYRO_SENSITIVITY_DPS_PER_LSB;
+        imuData.temperatureC = TEMP_OFFSET_C + (static_cast<float>(temp) / TEMP_SENSITIVITY_LSB_PER_C);
+        imuData.sensorTimestampTicks = makeUInt32(timestampRaw[0], timestampRaw[1], timestampRaw[2], timestampRaw[3]);
+        imuData.sensorTimestampTickSeconds = SENSOR_TIMESTAMP_TICK_S;
+        imuData.sensorTimestampValid = true;
         return true;

diff --git a/src/filters.cpp b/src/filters.cpp
@@ helper dt hardware @@
+    float computeImuDeltaTimeSeconds(const RawImuData& imuData, FilterState& state)
+    {
+        float dtSeconds = 0.0f;
+
+        if (imuData.sensorTimestampValid &&
+            state.lastSensorTimestampValid &&
+            imuData.sensorTimestampTickSeconds > 0.0f)
+        {
+            const uint32_t deltaTicks = imuData.sensorTimestampTicks - state.lastSensorTimestampTicks;
+            if (deltaTicks > 0U)
+            {
+                dtSeconds = static_cast<float>(deltaTicks) * imuData.sensorTimestampTickSeconds;
+            }
+        }
+
+        if (imuData.sensorTimestampValid)
+        {
+            state.lastSensorTimestampTicks = imuData.sensorTimestampTicks;
+            state.lastSensorTimestampValid = true;
+        }
+        else
+        {
+            state.lastSensorTimestampTicks = 0;
+            state.lastSensorTimestampValid = false;
+        }
+
+        return dtSeconds;
+    }
@@ begin @@
-        state.gyroRuntimeBiasZDegS = 0.0f;
-        state.lastUpdateMs = 0;
+        state.gyroRuntimeBiasZDegS = 0.0f;
+        state.lastSensorTimestampTicks = 0;
+        state.lastUpdateMs = 0;
+        state.lastSensorTimestampValid = false;
         state.initialized = false;
@@ update @@
-        float dt = DEFAULT_DT_S;
-        if (state.lastUpdateMs != 0 && nowMs > state.lastUpdateMs)
+        float dt = computeImuDeltaTimeSeconds(imuData, state);
+        if (!(dt > 0.0f))
         {
-            dt = (nowMs - state.lastUpdateMs) / 1000.0f;
+            dt = DEFAULT_DT_S;
+            if (state.lastUpdateMs != 0 && nowMs > state.lastUpdateMs)
+            {
+                dt = (nowMs - state.lastUpdateMs) / 1000.0f;
+            }
         }
@@ runtime live @@
         const RawImuData bodyFrameImu = AxisMap::mapToBodyFrame(config.axisMap, imuData);
+        runtimeState.imuTimestampTicks = bodyFrameImu.sensorTimestampTicks;
+        runtimeState.imuTemperatureC = bodyFrameImu.temperatureC;
         const float accelAlpha = clamp01(config.accelLpfAlpha);

diff --git a/src/filters.h b/src/filters.h
@@
-    // Aggiorna la stima di assetto e i valori runtime a partire dall'ultimo campione IMU,
-    // dopo il remap nel frame veicolo canonico.
+    // Aggiorna la stima di assetto e i valori runtime a partire dall'ultimo campione IMU,
+    // usando prioritariamente il timestamp hardware del sensore per il dt
+    // e propagando nel live anche timestamp e temperatura IMU
+    // dopo il remap nel frame veicolo canonico.
     void update(const AppConfig& config, const RawImuData& imuData, const ReferenceData& reference, FilterState& state, RuntimeState& runtimeState);

diff --git a/src/inclinometro_esp32.ino b/src/inclinometro_esp32.ino
@@ clearInstantTelemetry @@
+        runtimeState.imuTimestampTicks = 0;
         runtimeState.rollDeg = 0.0f;
         runtimeState.pitchDeg = 0.0f;
         runtimeState.rawRollDeg = 0.0f;
         runtimeState.rawPitchDeg = 0.0f;
         runtimeState.longitudinalAccelG = 0.0f;
+        runtimeState.imuTemperatureC = 0.0f;
         runtimeState.sampleRateHz = 0.0f;
         runtimeState.imuReady = false;

diff --git a/src/comm_shared.h b/src/comm_shared.h
@@
-static constexpr const char* COMM_PROTOCOL_VERSION = "4.5";
+static constexpr const char* COMM_PROTOCOL_VERSION = "4.6";

diff --git a/src/comm_protocol.cpp b/src/comm_protocol.cpp
@@ makeRuntimeStateJson @@
         String json = "{\"ts\":";
         json += String(runtimeState.timestampMs);
+        json += ",\"its\":";
+        json += String(static_cast<unsigned long>(runtimeState.imuTimestampTicks));
         json += ",\"r\":";
         json += String(runtimeState.rollDeg, 2);
         json += ",\"p\":";
         json += String(runtimeState.pitchDeg, 2);
         json += ",\"rr\":";
         json += String(runtimeState.rawRollDeg, 2);
         json += ",\"rp\":";
         json += String(runtimeState.rawPitchDeg, 2);
         json += ",\"ay\":";
         json += String(runtimeState.longitudinalAccelG, 3);
+        json += ",\"itc\":";
+        json += String(runtimeState.imuTemperatureC, 2);
         json += ",\"ir\":";
         json += runtimeState.imuReady ? "true" : "false";
```

## 2026-05-16 - Uso GPS opzionale nei filtri

```diff
diff --git a/src/inclinometro_esp32.ino b/src/inclinometro_esp32.ino
@@
+    // Converte l'ultimo fix GNSS in ausilio cinematico per i filtri.
+    // Se il GPS non e presente, non sta streamando o il fix non e valido,
+    // restituisce valid=false e la pipeline resta IMU-only.
+    GpsAidData makeGpsAidFromCurrentFix()
+    {
+        GpsFixData gpsFix {};
+        GpsAidData gpsAid {};
+        const bool hasFix = Gps::readFix(gpsFix);
+        if (hasFix && Gps::streaming() && gpsFix.valid && Gps::fixAgeMs() < GPS_FIX_STALE_TIMEOUT_MS)
+        {
+            const float groundSpeedMs = gpsFix.groundSpeedMmS > 0
+                ? gpsFix.groundSpeedMmS * 0.001f
+                : 0.0f;
+            gpsAid.speedMs = groundSpeedMs;
+            gpsAid.valid = true;
+        }
+        return gpsAid;
+    }
@@
-    // Aggiorna i dati GPS per la pipeline filtri (sensor task su Core1).
+    // Aggiorna i dati GPS opzionali per la pipeline filtri.
@@
-    // il sensor task degrada automaticamente al comportamento IMU-only senza nessuna modifica.
+    // In ogni altro caso gpsAid.valid resta false e il sensor task lavora in modalita IMU-only.
@@
-        GpsFixData gpsFix {};
-        GpsAidData newGpsAid {};
-        const bool hasFix = Gps::readFix(gpsFix);
-        if (hasFix && Gps::streaming() && gpsFix.gnssFixOk && !gpsFix.invalidLlh &&
-            Gps::fixAgeMs() < GPS_FIX_STALE_TIMEOUT_MS)
-        {
-            newGpsAid.speedMs = gpsFix.groundSpeedMmS * 0.001f;
-            newGpsAid.valid = true;
-        }
+        const GpsAidData newGpsAid = makeGpsAidFromCurrentFix();

diff --git a/src/filters.h b/src/filters.h
@@
-    // la pipeline degrada automaticamente al comportamento IMU-only senza effetti collaterali.
+    // quando valid=false la pipeline resta IMU-only.

diff --git a/src/types.h b/src/types.h
@@
-    // Dati GPS validati e pronti per l'uso nella pipeline filtri.
+    // Dati GPS validati e pronti per l'uso opzionale nella pipeline filtri.
@@
-    // in quel caso speedMs viene ignorato e la pipeline funziona come senza GPS.
+    // in quel caso speedMs viene ignorato e la pipeline funziona come IMU-only.

diff --git a/src/filters.cpp b/src/filters.cpp
@@
-        // non viene applicata e la pipeline si comporta esattamente come senza GPS.
+        // non viene applicata e la pipeline si comporta esattamente come IMU-only.
```

## 2026-03-31 - Stato GPS usabile per l'app e rimozione live_includes_gps
```diff
diff --git a/src/comm_shared.h b/src/comm_shared.h
@@
-static constexpr const char* COMM_PROTOCOL_VERSION = "4.10";
+static constexpr const char* COMM_PROTOCOL_VERSION = "4.11";

diff --git a/src/comm_protocol.h b/src/comm_protocol.h
@@
-    // Restituisce il payload informativo statico del firmware e della board,
-    // incluso il metadata OTA con build, timestamp, versione protocollo
-    // e capability dichiarate del payload live.
+    // Restituisce il payload informativo statico del firmware e della board,
+    // incluso il metadata OTA con build, timestamp e versione protocollo.
     String makeInfoJson(const String& requestId = "");
@@
-    // Serializza lo stato operativo complessivo del firmware senza metriche peak runtime,
-    // inclusa la presenza del modulo GPS, lo stato del parser NAV-PVT
-    // e la capability live GPS.
+    // Serializza lo stato operativo complessivo del firmware senza metriche peak runtime,
+    // inclusa la presenza del modulo GPS, lo stato del parser NAV-PVT
+    // e l'indicazione di usabilita corrente del fix.
     String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId = "");

diff --git a/src/comm_protocol.cpp b/src/comm_protocol.cpp
@@ makeInfoJson @@
-    // Espone metadati statici del firmware, della board, del trasporto BLE
-    // e le capability dichiarate del payload live.
+    // Espone metadati statici del firmware, della board e del trasporto BLE.
     String makeInfoJson(const String& requestId)
@@
         payload += jsonStringLiteral(BLE_LIVE_TX_UUID);
         payload += ",\"ota_data_rx_uuid\":";
         payload += jsonStringLiteral(BLE_OTA_DATA_RX_UUID);
         payload += ",\"pairing_required\":true,\"pairing_mode\":\"static_passkey\",\"ota_ble_enabled\":true}";
-        payload += ",\"live_includes_gps\":";
-        payload += COMM_LIVE_INCLUDES_GPS ? "true" : "false";
         return buildEnvelope("info", requestId, Messages::Protocol::kStatoOk, payload);
     }
@@ makeStatusJson @@
-    // Compone il quadro operativo completo usato per diagnostica e health check,
-    // inclusa la presenza del modulo GPS opzionale quando rilevato su UART
-    // e lo stato del parser NAV-PVT, mentre il live espone anche un sottoinsieme GPS compact.
+    // Compone il quadro operativo completo usato per diagnostica e health check,
+    // inclusa la presenza del modulo GPS opzionale quando rilevato su UART
+    // e lo stato del parser NAV-PVT, mentre il live porta sempre i campi GPS compact.
     String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId)
@@
         payload += ",\"gps_streaming\":";
         payload += gpsStreaming ? "true" : "false";
-        payload += ",\"gps_fix_ok\":";
+        payload += ",\"gps_usable\":";
         payload += (gpsHasFix && gpsStreaming && gpsFix.valid) ? "true" : "false";
         payload += ",\"gps_fix_type\":";
         payload += String(static_cast<unsigned int>(gpsHasFix ? gpsFix.fixType : 0U));
         payload += ",\"gps_num_sv\":";
         payload += String(static_cast<unsigned int>(gpsHasFix ? gpsFix.numSv : 0U));
         payload += ",\"gps_fix_age_ms\":";
         payload += String(static_cast<unsigned long>(gpsHasFix ? Gps::fixAgeMs() : 0U));
-        payload += ",\"live_includes_gps\":";
-        payload += COMM_LIVE_INCLUDES_GPS ? "true" : "false";
         payload += ",\"i2c_address\":";
         payload += jsonStringLiteral(i2cAddress);

diff --git a/src/comm.h b/src/comm.h
@@
-    // Inizializza il sottosistema di comunicazione e avvia il trasporto BLE.
-    // Le capability dichiarate del protocollo, incluso il live GPS, restano centralizzate nel serializer.
+    // Inizializza il sottosistema di comunicazione e avvia il trasporto BLE.
+    // Lo schema del protocollo live e centralizzato nel serializer.
     void begin();

diff --git a/src/comm.cpp b/src/comm.cpp
@@
-    // Avvia il layer trasporto usando la configurazione runtime corrente.
-    // Le informazioni statiche di schema live vengono poi serializzate da CommProtocol.
+    // Avvia il layer trasporto usando la configurazione runtime corrente.
+    // Le informazioni statiche e dinamiche di protocollo vengono serializzate da CommProtocol.
     void begin()
     {
         CommTransport::begin(g_transportRuntime);
     }

diff --git a/src/messages.h b/src/messages.h
@@
-// - nomi e semantica dei campi compact live documentati altrove, inclusi its / itc
-//   e l'estensione GPS compact quando il protocollo la dichiara attiva
-// - capability dichiarate del protocollo come gps_present e live_includes_gps
+// - nomi e semantica dei campi compact live documentati altrove, inclusi its / itc
+//   e l'estensione GPS compact
+// - campi di stato GPS come gps_present e gps_usable
 // - descrizioni dei tipi attesi in validazione JSON

diff --git a/src/messages.cpp b/src/messages.cpp
@@
 // Implementazione del catalogo testi del firmware.
 // Tenere qui le stringhe evita hardcode sparsi e rende piu semplice
 // uniformare tono, lingua e documentazione del protocollo anche quando il payload cambia,
-// inclusa l'estensione del compact live, i campi GPS compact e le capability dichiarate all'app.
+// inclusa l'estensione del compact live, i campi GPS compact e lo stato GPS dichiarato all'app.
 namespace Messages
```

## 2026-03-31 - Live compact con dati GPS
```diff
diff --git a/src/comm_shared.h b/src/comm_shared.h
@@
-static constexpr const char* COMM_PROTOCOL_VERSION = "4.9";
-static constexpr bool COMM_LIVE_INCLUDES_GPS = false;
+static constexpr const char* COMM_PROTOCOL_VERSION = "4.10";
+static constexpr bool COMM_LIVE_INCLUDES_GPS = true;

diff --git a/src/comm_protocol.h b/src/comm_protocol.h
@@
     // Restituisce lo stato corrente del flusso OTA via BLE.
     String makeOtaStatusJson(const CommTransportRuntime& runtime);
     // Serializza la telemetria runtime nel formato compact del protocollo,
-    // inclusi i metadati istantanei IMU necessari al client.
+    // inclusi i metadati istantanei IMU e il sottoinsieme GPS compact
+    // esposto all'app quando il parser NAV-PVT e attivo.
     String makeRuntimeStateJson(const RuntimeState& runtimeState);

diff --git a/src/comm_protocol.cpp b/src/comm_protocol.cpp
@@ makeRuntimeStateJson @@
     // Serializza lo snapshot runtime nel formato compact del protocollo.
     String makeRuntimeStateJson(const RuntimeState& runtimeState)
     {
         // Formato corto pensato per l'app durante lo streaming continuo,
         // limitato ai soli valori istantanei necessari.
+        GpsFixData gpsFix {};
+        const bool gpsHasFix = Gps::readFix(gpsFix);
+        const bool gpsStreaming = Gps::streaming();
+        if (!gpsHasFix || !gpsStreaming)
+        {
+            gpsFix = {};
+        }
+
         String json = "{\"ts\":";
@@
         json += ",\"itc\":";
         json += String(runtimeState.imuTemperatureC, 2);
         json += ",\"ir\":";
         json += runtimeState.imuReady ? "true" : "false";
+        json += ",\"git\":";
+        json += String(static_cast<unsigned long>(gpsFix.iTowMs));
+        json += ",\"gok\":";
+        json += (gpsHasFix && gpsStreaming && gpsFix.valid) ? "true" : "false";
+        json += ",\"gft\":";
+        json += String(static_cast<unsigned int>(gpsFix.fixType));
+        json += ",\"gsv\":";
+        json += String(static_cast<unsigned int>(gpsFix.numSv));
+        json += ",\"glon\":";
+        json += String(gpsFix.longitudeDegE7);
+        json += ",\"glat\":";
+        json += String(gpsFix.latitudeDegE7);
+        json += ",\"gmsl\":";
+        json += String(gpsFix.heightMslMm);
+        json += ",\"gha\":";
+        json += String(static_cast<unsigned long>(gpsFix.horizontalAccMm));
+        json += ",\"gvn\":";
+        json += String(gpsFix.velNorthMmS);
+        json += ",\"gve\":";
+        json += String(gpsFix.velEastMmS);
+        json += ",\"gvd\":";
+        json += String(gpsFix.velDownMmS);
+        json += ",\"gsp\":";
+        json += String(gpsFix.groundSpeedMmS);
+        json += ",\"ghm\":";
+        json += String(gpsFix.headingMotionDegE5);
+        json += ",\"gsa\":";
+        json += String(static_cast<unsigned long>(gpsFix.speedAccMmS));
+        json += ",\"ghd\":";
+        json += String(static_cast<unsigned long>(gpsFix.headingAccDegE5));
         json += "}";
         return json;
     }
@@
-    // e lo stato del parser NAV-PVT, mantenendo il live privo di campi GPS.
+    // e lo stato del parser NAV-PVT, mentre il live espone anche un sottoinsieme GPS compact.
     String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId)

diff --git a/src/comm.h b/src/comm.h
@@
     // Invia lo snapshot runtime corrente al client BLE attivo, se presente.
     // Il payload live compact espone i valori istantanei consentiti dal protocollo,
     // inclusi timestamp e temperatura della IMU.
-    // Lo stato del parser GPS resta confinato a info/status e non entra nel live.
+    // Quando il parser NAV-PVT e attivo, il live include anche i campi GPS compact dichiarati dal protocollo.
     // Durante OTA il layer trasporto puo sospendere temporaneamente le notify live.
     void sendRuntimeState(const RuntimeState& runtimeState);

diff --git a/src/comm.cpp b/src/comm.cpp
@@
     // Propaga la telemetria runtime compact al trasporto senza esporre dettagli GATT.
     // Il payload resta limitato ai valori istantanei previsti dal protocollo,
     // inclusi i nuovi metadati IMU del campione corrente.
-    // I dettagli diagnostici del parser GPS restano invece nel solo status BLE.
+    // Se abilitato dal protocollo corrente, il live include anche il sottoinsieme GPS compact.
     // Il trasporto puo sospendere queste notify mentre un OTA e in corso.
     void sendRuntimeState(const RuntimeState& runtimeState)
     {

diff --git a/src/comm_transport.h b/src/comm_transport.h
@@
     // Fa avanzare il trasporto BLE usando il contesto applicativo corrente.
     void handleIncoming(const CommRequestContext& context, CommTransportRuntime& runtime);
     // Invia telemetria live al client BLE autenticato e sottoscritto,
     // compresi timestamp e temperatura IMU del campione corrente.
-    // L'eventuale stato del parser GPS viene invece letto via comandi info/status.
+    // Se previsto dal protocollo corrente, include anche i campi GPS compact nel payload live.
     // Durante un OTA in corso le notify live vengono sospese per lasciare banda al trasferimento.
     void sendRuntimeState(const RuntimeState& runtimeState);

diff --git a/src/comm_transport.cpp b/src/comm_transport.cpp
@@
-        // Il framing BLE resta invariato: cambia solo il contenuto del compact live.
+        // Il framing BLE resta invariato: cambia solo il contenuto del compact live,
+        // che puo includere anche i campi GPS compact previsti dal protocollo.
         notifyFramed(g_liveTxCharacteristic, CommProtocol::makeRuntimeStateJson(runtimeState));
     }
 }

diff --git a/src/messages.h b/src/messages.h
@@
-// - nomi e semantica dei campi compact live documentati altrove, inclusi its / itc
+// - nomi e semantica dei campi compact live documentati altrove, inclusi its / itc
+//   e l'estensione GPS compact quando il protocollo la dichiara attiva
 // - capability dichiarate del protocollo come gps_present e live_includes_gps
 // - descrizioni dei tipi attesi in validazione JSON

diff --git a/src/messages.cpp b/src/messages.cpp
@@
 // Implementazione del catalogo testi del firmware.
 // Tenere qui le stringhe evita hardcode sparsi e rende piu semplice
 // uniformare tono, lingua e documentazione del protocollo anche quando il payload cambia,
-// inclusa l'estensione del compact live e delle capability dichiarate all'app.
+// inclusa l'estensione del compact live, i campi GPS compact e le capability dichiarate all'app.
 namespace Messages
```

## 2026-03-29 - OTA BLE: sospensione del live durante l'update
```diff
diff --git a/src/comm_transport.cpp b/src/comm_transport.cpp
@@ void sendRuntimeState @@
-        if (!liveChannelReady())
+        if (g_runtime == nullptr || g_runtime->updateInProgress || !liveChannelReady())
         {
             return;
         }

diff --git a/src/comm_transport.h b/src/comm_transport.h
@@
-    // Invia telemetria live al client BLE autenticato e sottoscritto,
-    // compresi timestamp e temperatura IMU del campione corrente.
+    // Invia telemetria live al client BLE autenticato e sottoscritto,
+    // compresi timestamp e temperatura IMU del campione corrente.
+    // Durante un OTA in corso le notify live vengono sospese per lasciare banda al trasferimento.
     void sendRuntimeState(const RuntimeState& runtimeState);

diff --git a/src/comm.h b/src/comm.h
@@
-    // Invia lo snapshot runtime corrente al client BLE attivo, se presente.
-    // Il payload live compact espone i valori istantanei consentiti dal protocollo,
-    // inclusi timestamp e temperatura della IMU.
+    // Invia lo snapshot runtime corrente al client BLE attivo, se presente.
+    // Il payload live compact espone i valori istantanei consentiti dal protocollo,
+    // inclusi timestamp e temperatura della IMU.
+    // Durante OTA il layer trasporto puo sospendere temporaneamente le notify live.
     void sendRuntimeState(const RuntimeState& runtimeState);

diff --git a/src/comm.cpp b/src/comm.cpp
@@
-    // Propaga la telemetria runtime compact al trasporto senza esporre dettagli GATT.
-    // Il payload resta limitato ai valori istantanei previsti dal protocollo,
-    // inclusi i nuovi metadati IMU del campione corrente.
+    // Propaga la telemetria runtime compact al trasporto senza esporre dettagli GATT.
+    // Il payload resta limitato ai valori istantanei previsti dal protocollo,
+    // inclusi i nuovi metadati IMU del campione corrente.
+    // Il trasporto puo sospendere queste notify mentre un OTA e in corso.
     void sendRuntimeState(const RuntimeState& runtimeState)
     {
         CommTransport::sendRuntimeState(runtimeState);
     }
```

## 2026-03-30 - Predisposizione UART GPS su GPIO19/GPIO20
```diff
diff --git a/src/config.h b/src/config.h
@@
+// UART dedicata opzionale per un modulo GNSS esterno tipo SAM-M10Q.
+// Il firmware apre solo la connessione seriale: nessun dato GPS entra ancora
+// nella logica applicativa o nella telemetria BLE.
+static constexpr bool GPS_UART_ENABLED = true;
+static constexpr int GPS_UART_RX_PIN = 19; // ESP32 RX <- TX del GPS
+static constexpr int GPS_UART_TX_PIN = 20; // ESP32 TX -> RX del GPS
+static constexpr uint32_t GPS_UART_BAUDRATE = 9600;

diff --git a/src/messages.h b/src/messages.h
@@
+        // Messaggio emesso quando la UART GPS dedicata viene inizializzata.
+        extern const char* const kGpsUartStarted;

diff --git a/src/messages.cpp b/src/messages.cpp
@@
+        const char* const kGpsUartStarted = "UART GPS pronta su GP19/GP20 a 9600 baud";

diff --git a/src/gps.h b/src/gps.h
@@
+#pragma once
+
+#include <HardwareSerial.h>
+
+// Modulo di predisposizione UART per un ricevitore GNSS esterno.
+// Qui non vengono fatti parsing NMEA, fix o logica GPS: il firmware
+// si limita ad aprire la porta seriale dedicata e a renderla disponibile.
+namespace Gps
+{
+    // Inizializza la UART dedicata al modulo GNSS se abilitata dal profilo hardware.
+    void begin();
+    // Indica se la predisposizione UART GPS e attiva nel profilo corrente.
+    bool enabled();
+    // Espone la UART dedicata per futuri moduli che vorranno leggere il GNSS.
+    HardwareSerial& serial();
+}

diff --git a/src/gps.cpp b/src/gps.cpp
@@
+#include "gps.h"
+
+#include "config.h"
+#include "debug.h"
+#include "messages.h"
+
+namespace
+{
+    HardwareSerial g_gpsSerial(1);
+    bool g_started = false;
+}
+
+namespace Gps
+{
+    void begin()
+    {
+        if (!GPS_UART_ENABLED || g_started)
+        {
+            return;
+        }
+
+        g_gpsSerial.begin(GPS_UART_BAUDRATE, SERIAL_8N1, GPS_UART_RX_PIN, GPS_UART_TX_PIN);
+        g_started = true;
+        Debug::info(Messages::Log::kGpsUartStarted);
+    }
+
+    bool enabled()
+    {
+        return GPS_UART_ENABLED;
+    }
+
+    HardwareSerial& serial()
+    {
+        return g_gpsSerial;
+    }
+}

diff --git a/src/inclinometro_esp32.ino b/src/inclinometro_esp32.ino
@@
-// 3) inizializzazione del sensore LSM6DSOX
+// 3) inizializzazione del sensore LSM6DSOX e della UART GPS opzionale
 // 4) avvio task dedicato ad acquisizione e filtri
 // 5) loop principale riservato a comunicazione e telemetria
@@
+#include "gps.h"
@@
+    Gps::begin();
     Comm::begin();
```

## 2026-03-31 - Configurazione runtime SAM-M10Q a 10 Hz e UART 115200
```diff
diff --git a/src/config.h b/src/config.h
@@
-// UART dedicata opzionale per un modulo GNSS esterno tipo SAM-M10Q.
-// Il firmware apre solo la connessione seriale: nessun dato GPS entra ancora
-// nella logica applicativa o nella telemetria BLE.
+// UART dedicata opzionale per un modulo GNSS esterno tipo SAM-M10Q.
+// Il firmware prova il bootstrap sul baudrate factory tipico del modulo
+// e poi lo riconfigura a runtime via UBX in sola RAM.
 static constexpr bool GPS_UART_ENABLED = true;
 static constexpr int GPS_UART_RX_PIN = 19; // ESP32 RX <- TX del GPS
 static constexpr int GPS_UART_TX_PIN = 20; // ESP32 TX -> RX del GPS
-static constexpr uint32_t GPS_UART_BAUDRATE = 9600;
+static constexpr uint32_t GPS_UART_FACTORY_BAUDRATE = 38400;
+static constexpr uint32_t GPS_UART_LEGACY_FALLBACK_BAUDRATE = 9600;
+static constexpr uint32_t GPS_UART_BAUDRATE = 115200;
+static constexpr uint16_t GPS_NAV_MEAS_RATE_MS = 100; // 10 Hz
+static constexpr uint8_t GPS_NAV_PVT_RATE = 1;
+static constexpr uint8_t GPS_NAV_DYNAMIC_MODEL = 4; // AUTOMOT
+static constexpr bool GPS_SAM_M10Q_PROGRAM_HIGH_CPU_CLOCK_OTP = false;

diff --git a/src/messages.h b/src/messages.h
@@
-        // Messaggio emesso quando la UART GPS dedicata viene inizializzata.
+        // Messaggio emesso quando parte la configurazione della UART GPS dedicata.
         extern const char* const kGpsUartStarted;
+        // Messaggio emesso quando la configurazione runtime del SAM-M10Q va a buon fine.
+        extern const char* const kGpsConfigured;
+        // Messaggio emesso quando la configurazione runtime del SAM-M10Q non viene confermata.
+        extern const char* const kGpsConfigFailed;

diff --git a/src/messages.cpp b/src/messages.cpp
@@
-        const char* const kGpsUartStarted = "UART GPS pronta su GP19/GP20 a 9600 baud";
+        const char* const kGpsUartStarted = "Configurazione SAM-M10Q su UART GPS in corso";
+        const char* const kGpsConfigured = "SAM-M10Q configurato: UBX-NAV-PVT 10 Hz, UART 115200, modello Automotive";
+        const char* const kGpsConfigFailed = "ATTENZIONE: configurazione SAM-M10Q non confermata";

diff --git a/src/gps.h b/src/gps.h
@@
-// Modulo di predisposizione UART per un ricevitore GNSS esterno.
-// Qui non vengono fatti parsing NMEA, fix o logica GPS: il firmware
-// si limita ad aprire la porta seriale dedicata e a renderla disponibile.
+// Modulo di configurazione UART per un ricevitore GNSS esterno tipo SAM-M10Q.
+// Il firmware configura il ricevitore a runtime via UBX, ma non usa ancora i dati GPS
+// nella logica applicativa o nella telemetria BLE.
 namespace Gps
 {
-    // Inizializza la UART dedicata al modulo GNSS se abilitata dal profilo hardware.
+    // Inizializza e configura la UART dedicata al modulo GNSS se abilitata dal profilo hardware.
     void begin();
+    // Mantiene scarica la RX UART finche non esiste un parser GPS reale nel firmware.
+    void update();
     // Indica se la predisposizione UART GPS e attiva nel profilo corrente.
     bool enabled();
+    // Indica se la configurazione runtime del SAM-M10Q e stata confermata via UBX.
+    bool configured();
     // Espone la UART dedicata per futuri moduli che vorranno leggere il GNSS.
     HardwareSerial& serial();
 }

diff --git a/src/gps.cpp b/src/gps.cpp
@@
-namespace
-{
-    HardwareSerial g_gpsSerial(1);
-    bool g_started = false;
-}
+namespace
+{
+    constexpr uint8_t UBX_SYNC_CHAR_1 = 0xB5;
+    constexpr uint8_t UBX_SYNC_CHAR_2 = 0x62;
+    constexpr uint8_t UBX_CLASS_ACK = 0x05;
+    constexpr uint8_t UBX_ID_ACK_ACK = 0x01;
+    constexpr uint8_t UBX_CLASS_CFG = 0x06;
+    constexpr uint8_t UBX_ID_CFG_VALSET = 0x8A;
+    constexpr uint8_t UBX_LAYER_RAM = 0x01;
+    constexpr uint8_t UBX_DRAIN_CHUNK_BYTES = 128;
+    constexpr uint32_t CFG_RATE_MEAS = 0x30210001;
+    constexpr uint32_t CFG_NAVSPG_DYNMODEL = 0x20110021;
+    constexpr uint32_t CFG_UART1_BAUDRATE = 0x40520001;
+    constexpr uint32_t CFG_MSGOUT_UBX_NAV_PVT_UART1 = 0x20910007;
+    constexpr uint32_t CFG_SIGNAL_GLO_ENA = 0x10310025;
+    constexpr uint32_t CFG_SIGNAL_BDS_B1C_ENA = 0x1031000F;
+    HardwareSerial g_gpsSerial(1);
+    bool g_started = false;
+    bool g_configured = false;
+}
@@
-namespace Gps
-{
-    void begin()
-    {
-        if (!GPS_UART_ENABLED || g_started)
-        {
-            return;
-        }
-
-        g_gpsSerial.begin(GPS_UART_BAUDRATE, SERIAL_8N1, GPS_UART_RX_PIN, GPS_UART_TX_PIN);
-        g_started = true;
-        Debug::info(Messages::Log::kGpsUartStarted);
-    }
+    template <size_t N>
+    bool sendValset(const ConfigItem (&items)[N], bool waitAck = true, uint32_t ackTimeoutMs = GPS_CONFIG_ACK_TIMEOUT_MS)
+    {
+        uint8_t payload[4U + (N * 8U)] = {};
+        size_t offset = 0;
+        appendU1(payload, offset, 0x00);
+        appendU1(payload, offset, UBX_LAYER_RAM);
+        appendU1(payload, offset, 0x00);
+        appendU1(payload, offset, 0x00);
+        for (const ConfigItem& item : items)
+        {
+            appendConfigItem(payload, offset, item);
+        }
+        drainRx();
+        if (!sendUbxFrame(UBX_CLASS_CFG, UBX_ID_CFG_VALSET, payload, offset))
+        {
+            return false;
+        }
+        if (!waitAck)
+        {
+            return true;
+        }
+        return waitForAck(UBX_CLASS_CFG, UBX_ID_CFG_VALSET, ackTimeoutMs);
+    }
@@
+    bool detectAndConfigureSamM10Q()
+    {
+        const uint32_t candidateBaudrates[] = {
+            GPS_UART_BAUDRATE,
+            GPS_UART_FACTORY_BAUDRATE,
+            GPS_UART_LEGACY_FALLBACK_BAUDRATE,
+        };
+        const ConfigItem probeConfig[] = {
+            {CFG_UART1INPROT_UBX, ConfigValueType::Bool, 1U},
+        };
+        for (uint32_t candidateBaudrate : candidateBaudrates)
+        {
+            g_gpsSerial.begin(candidateBaudrate, SERIAL_8N1, GPS_UART_RX_PIN, GPS_UART_TX_PIN);
+            delay(30);
+            if (!sendValset(probeConfig))
+            {
+                g_gpsSerial.end();
+                delay(20);
+                continue;
+            }
+            return configureReceiverAtCurrentBaud();
+        }
+        g_gpsSerial.begin(GPS_UART_BAUDRATE, SERIAL_8N1, GPS_UART_RX_PIN, GPS_UART_TX_PIN);
+        return false;
+    }
+
+namespace Gps
+{
+    void begin()
+    {
+        if (!GPS_UART_ENABLED || g_started)
+        {
+            return;
+        }
+        g_started = true;
+        Debug::info(Messages::Log::kGpsUartStarted);
+        g_configured = detectAndConfigureSamM10Q();
+        Debug::info(g_configured ? Messages::Log::kGpsConfigured : Messages::Log::kGpsConfigFailed);
+    }
+
+    void update()
+    {
+        if (!g_started)
+        {
+            return;
+        }
+        uint8_t drainedBytes = 0;
+        while (drainedBytes < UBX_DRAIN_CHUNK_BYTES && g_gpsSerial.available() > 0)
+        {
+            (void)g_gpsSerial.read();
+            ++drainedBytes;
+        }
+    }
@@
     bool enabled()
     {
         return GPS_UART_ENABLED;
     }
+
+    bool configured()
+    {
+        return g_configured;
+    }
 
diff --git a/src/inclinometro_esp32.ino b/src/inclinometro_esp32.ino
@@
-// 3) inizializzazione del sensore LSM6DSOX e della UART GPS opzionale
+// 3) inizializzazione del sensore LSM6DSOX e configurazione della UART GPS opzionale
 // 4) avvio task dedicato ad acquisizione e filtri
 // 5) loop principale riservato a comunicazione e telemetria
@@
+    Gps::update();
     vTaskDelay(pdMS_TO_TICKS(1));
```

## 2026-03-31 - Stato protocollo GPS: campo gps_present
```diff
diff --git a/src/gps.h b/src/gps.h
@@
     // Mantiene scarica la RX UART finche non esiste un parser GPS reale nel firmware.
     void update();
     // Indica se la predisposizione UART GPS e attiva nel profilo corrente.
     bool enabled();
+    // Indica se il modulo GNSS risponde sulla UART ed e stato rilevato dal firmware.
+    bool present();
     // Indica se la configurazione runtime del SAM-M10Q e stata confermata via UBX.
     bool configured();
     // Espone la UART dedicata per futuri moduli che vorranno leggere il GNSS.
     HardwareSerial& serial();

diff --git a/src/gps.cpp b/src/gps.cpp
@@
     HardwareSerial g_gpsSerial(1);
     bool g_started = false;
+    bool g_present = false;
     bool g_configured = false;
@@
         for (uint32_t candidateBaudrate : candidateBaudrates)
         {
             g_gpsSerial.begin(candidateBaudrate, SERIAL_8N1, GPS_UART_RX_PIN, GPS_UART_TX_PIN);
             delay(30);
             if (!sendValset(probeConfig))
@@
                 continue;
             }
 
+            g_present = true;
             return configureReceiverAtCurrentBaud();
         }
@@
         }
 
         g_started = true;
+        g_present = false;
+        g_configured = false;
         Debug::info(Messages::Log::kGpsUartStarted);
         g_configured = detectAndConfigureSamM10Q();
         Debug::info(g_configured ? Messages::Log::kGpsConfigured : Messages::Log::kGpsConfigFailed);
@@
     bool enabled()
     {
         return GPS_UART_ENABLED;
     }
 
+    bool present()
+    {
+        return g_present;
+    }
+
     bool configured()
     {
         return g_configured;
     }

diff --git a/src/comm_shared.h b/src/comm_shared.h
@@
-static constexpr const char* COMM_PROTOCOL_VERSION = "4.6";
+static constexpr const char* COMM_PROTOCOL_VERSION = "4.7";

diff --git a/src/comm_protocol.h b/src/comm_protocol.h
@@
-    // Serializza lo stato operativo complessivo del firmware senza metriche peak runtime.
+    // Serializza lo stato operativo complessivo del firmware senza metriche peak runtime,
+    // inclusa la presenza rilevata del modulo GPS opzionale.
     String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId = "");

diff --git a/src/comm_protocol.cpp b/src/comm_protocol.cpp
@@
 #include "axis_map.h"
 #include "config.h"
+#include "gps.h"
 #include "messages.h"
 #include "sensors.h"
@@
-    // Compone il quadro operativo completo usato per diagnostica e health check.
+    // Compone il quadro operativo completo usato per diagnostica e health check,
+    // inclusa la presenza del modulo GPS opzionale quando rilevato su UART.
     String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId)
@@
         payload += String(millis());
         payload += ",\"imu_ready\":";
         payload += runtimeState.imuReady ? "true" : "false";
+        payload += ",\"gps_present\":";
+        payload += Gps::present() ? "true" : "false";
         payload += ",\"i2c_address\":";
         payload += jsonStringLiteral(i2cAddress);
         payload += ",\"data_ready_mode\":";
```

## 2026-03-31 - Capability protocollo: live_includes_gps
```diff
diff --git a/src/comm_shared.h b/src/comm_shared.h
@@
-static constexpr const char* COMM_PROTOCOL_VERSION = "4.7";
+static constexpr const char* COMM_PROTOCOL_VERSION = "4.8";
+static constexpr bool COMM_LIVE_INCLUDES_GPS = false;

diff --git a/src/comm_protocol.h b/src/comm_protocol.h
@@
-    // Restituisce il payload informativo statico del firmware e della board,
-    // incluso il metadata OTA con build, timestamp e versione protocollo.
+    // Restituisce il payload informativo statico del firmware e della board,
+    // incluso il metadata OTA con build, timestamp, versione protocollo
+    // e capability dichiarate del payload live.
     String makeInfoJson(const String& requestId = "");
@@
-    // Serializza lo stato operativo complessivo del firmware senza metriche peak runtime,
-    // inclusa la presenza rilevata del modulo GPS opzionale.
+    // Serializza lo stato operativo complessivo del firmware senza metriche peak runtime,
+    // inclusa la presenza rilevata del modulo GPS opzionale e la capability live GPS.
     String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId = "");

diff --git a/src/comm_protocol.cpp b/src/comm_protocol.cpp
@@
-    // Espone metadati statici del firmware, della board e del trasporto BLE.
+    // Espone metadati statici del firmware, della board, del trasporto BLE
+    // e le capability dichiarate del payload live.
     String makeInfoJson(const String& requestId)
@@
         payload += jsonStringLiteral(BLE_OTA_DATA_RX_UUID);
         payload += ",\"pairing_required\":true,\"pairing_mode\":\"static_passkey\",\"ota_ble_enabled\":true}";
+        payload += ",\"live_includes_gps\":";
+        payload += COMM_LIVE_INCLUDES_GPS ? "true" : "false";
         return buildEnvelope("info", requestId, Messages::Protocol::kStatoOk, payload);
@@
-    // Compone il quadro operativo completo usato per diagnostica e health check,
-    // inclusa la presenza del modulo GPS opzionale quando rilevato su UART.
+    // Compone il quadro operativo completo usato per diagnostica e health check,
+    // inclusa la presenza del modulo GPS opzionale quando rilevato su UART
+    // e la dichiarazione sul fatto che il live includa o meno campi GPS.
     String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId)
@@
         payload += ",\"imu_ready\":";
         payload += runtimeState.imuReady ? "true" : "false";
         payload += ",\"gps_present\":";
         payload += Gps::present() ? "true" : "false";
+        payload += ",\"live_includes_gps\":";
+        payload += COMM_LIVE_INCLUDES_GPS ? "true" : "false";
         payload += ",\"i2c_address\":";
         payload += jsonStringLiteral(i2cAddress);

diff --git a/src/comm.h b/src/comm.h
@@
-    // Inizializza il sottosistema di comunicazione e avvia il trasporto BLE.
+    // Inizializza il sottosistema di comunicazione e avvia il trasporto BLE.
+    // Le capability dichiarate del protocollo, incluso il live GPS, restano centralizzate nel serializer.
     void begin();

diff --git a/src/comm.cpp b/src/comm.cpp
@@
-    // Avvia il layer trasporto usando la configurazione runtime corrente.
+    // Avvia il layer trasporto usando la configurazione runtime corrente.
+    // Le informazioni statiche di schema live vengono poi serializzate da CommProtocol.
     void begin()
     {
         CommTransport::begin(g_transportRuntime);
     }

diff --git a/src/comm_transport.h b/src/comm_transport.h
@@
-    // Inizializza il server BLE GATT, il pairing e l'advertising.
+    // Inizializza il server BLE GATT, il pairing e l'advertising.
+    // Non decide lo schema dei payload: quello resta nel layer protocollo.
     void begin(CommTransportRuntime& runtime);

diff --git a/src/messages.h b/src/messages.h
@@
-// - nomi e semantica dei campi compact live documentati altrove, inclusi its / itc
+// - nomi e semantica dei campi compact live documentati altrove, inclusi its / itc
+// - capability dichiarate del protocollo come gps_present e live_includes_gps
 // - descrizioni dei tipi attesi in validazione JSON

diff --git a/src/messages.cpp b/src/messages.cpp
@@
-// uniformare tono, lingua e documentazione del protocollo anche quando il payload cambia,
-// inclusa l'estensione del compact live.
+// uniformare tono, lingua e documentazione del protocollo anche quando il payload cambia,
+// inclusa l'estensione del compact live e delle capability dichiarate all'app.
 namespace Messages
```

## 2026-03-31 - Parser GPS UBX-NAV-PVT e status protocollo 4.9
```diff
diff --git a/src/types.h b/src/types.h
@@
 struct RawImuData
 {
     // Dati gia convertiti in unita fisiche, ma non ancora filtrati.
     // Il timestamp sensore descrive il campione acquisito e non cambia con il remap assi.
     float accelX = 0.0f;
@@
     bool sensorTimestampValid = false;
 };
+
+struct GpsFixData
+{
+    // Ultimo frame UBX-NAV-PVT decodificato dal ricevitore GNSS.
+    // I campi restano in unita native semplici per evitare conversioni ripetute.
+    uint32_t iTowMs = 0;
+    uint32_t receivedAtMs = 0;
+    int32_t longitudeDegE7 = 0;
+    int32_t latitudeDegE7 = 0;
+    int32_t heightMslMm = 0;
+    uint32_t horizontalAccMm = 0;
+    int32_t velNorthMmS = 0;
+    int32_t velEastMmS = 0;
+    int32_t velDownMmS = 0;
+    int32_t groundSpeedMmS = 0;
+    int32_t headingMotionDegE5 = 0;
+    uint32_t speedAccMmS = 0;
+    uint32_t headingAccDegE5 = 0;
+    uint8_t fixType = 0;
+    uint8_t numSv = 0;
+    bool validDate = false;
+    bool validTime = false;
+    bool gnssFixOk = false;
+    bool invalidLlh = false;
+    bool valid = false;
+};

diff --git a/src/config.h b/src/config.h
@@
-// e poi lo riconfigura a runtime via UBX in sola RAM.
+// e poi lo riconfigura a runtime via UBX in sola RAM, con parser NAV-PVT.
 static constexpr bool GPS_UART_ENABLED = true;
 static constexpr int GPS_UART_RX_PIN = 19; // ESP32 RX <- TX del GPS
 static constexpr int GPS_UART_TX_PIN = 20; // ESP32 TX -> RX del GPS
@@
 static constexpr uint16_t GPS_NAV_MEAS_RATE_MS = 100; // 10 Hz
 static constexpr uint8_t GPS_NAV_PVT_RATE = 1;
 static constexpr uint8_t GPS_NAV_DYNAMIC_MODEL = 4; // AUTOMOT
+static constexpr uint32_t GPS_FIX_STALE_TIMEOUT_MS = 1500;
 // Il manuale SAM-M10Q descrive il "high CPU clock" solo come scrittura OTP permanente.
 // Tenerlo disabilitato evita modifiche irreversibili del modulo da firmware.
 static constexpr bool GPS_SAM_M10Q_PROGRAM_HIGH_CPU_CLOCK_OTP = false;

diff --git a/src/gps.h b/src/gps.h
@@
 #pragma once
 
 #include <HardwareSerial.h>
+
+#include "types.h"
 
 // Modulo di configurazione UART per un ricevitore GNSS esterno tipo SAM-M10Q.
-// Il firmware configura il ricevitore a runtime via UBX, ma non usa ancora i dati GPS
-// nella logica applicativa o nella telemetria BLE.
+// Il firmware configura il ricevitore a runtime via UBX e ne decodifica i frame NAV-PVT,
+// senza usare i dati GPS nei filtri di inclinazione o nel payload live.
 namespace Gps
 {
@@
-    // Mantiene scarica la RX UART finche non esiste un parser GPS reale nel firmware.
+    // Fa avanzare il parser UBX e aggiorna l'ultimo fix GPS disponibile.
     void update();
@@
     // Indica se la configurazione runtime del SAM-M10Q e stata confermata via UBX.
     bool configured();
-    // Espone la UART dedicata per futuri moduli che vorranno leggere il GNSS.
+    // Indica se il firmware sta ricevendo frame NAV-PVT recenti dal modulo.
+    bool streaming();
+    // Copia l'ultimo fix NAV-PVT decodificato se disponibile.
+    bool readFix(GpsFixData& out);
+    // Restituisce l'eta del fix piu recente in millisecondi.
+    uint32_t fixAgeMs();
+    // Espone la UART dedicata per eventuali moduli diagnostici futuri.
     HardwareSerial& serial();
 }

diff --git a/src/gps.cpp b/src/gps.cpp
@@ costanti UBX @@
     constexpr uint8_t UBX_SYNC_CHAR_1 = 0xB5;
     constexpr uint8_t UBX_SYNC_CHAR_2 = 0x62;
+    constexpr uint8_t UBX_CLASS_NAV = 0x01;
+    constexpr uint8_t UBX_ID_NAV_PVT = 0x07;
     constexpr uint8_t UBX_CLASS_ACK = 0x05;
@@
-    constexpr uint8_t UBX_DRAIN_CHUNK_BYTES = 128;
+    constexpr uint16_t UBX_NAV_PVT_PAYLOAD_LENGTH = 92;
+    constexpr size_t UBX_PARSE_CHUNK_BYTES = 192;
@@ stato GPS @@
     HardwareSerial g_gpsSerial(1);
     bool g_started = false;
     bool g_present = false;
     bool g_configured = false;
+    bool g_streaming = false;
+    bool g_hasFix = false;
+    GpsFixData g_latestFix {};
+    UbxParserState g_parser {};
@@ helper parser @@
+    bool hasFreshFixAt(uint32_t nowMs)
+    {
+        return g_hasFix && (nowMs - g_latestFix.receivedAtMs) <= GPS_FIX_STALE_TIMEOUT_MS;
+    }
+
+    bool isUsableFix(const GpsFixData& fix)
+    {
+        return fix.gnssFixOk &&
+               !fix.invalidLlh &&
+               (fix.fixType == 2U || fix.fixType == 3U || fix.fixType == 4U);
+    }
+
+    void decodeNavPvt(const uint8_t* payload)
+    {
+        GpsFixData fix {};
+        const uint8_t validFlags = payload[11];
+        const uint8_t fixFlags = payload[21];
+        const uint16_t flags3 = readU2(payload, 78);
+
+        fix.iTowMs = readU4(payload, 0);
+        fix.receivedAtMs = millis();
+        fix.longitudeDegE7 = readI4(payload, 24);
+        fix.latitudeDegE7 = readI4(payload, 28);
+        fix.heightMslMm = readI4(payload, 36);
+        fix.horizontalAccMm = readU4(payload, 40);
+        fix.velNorthMmS = readI4(payload, 48);
+        fix.velEastMmS = readI4(payload, 52);
+        fix.velDownMmS = readI4(payload, 56);
+        fix.groundSpeedMmS = readI4(payload, 60);
+        fix.headingMotionDegE5 = readI4(payload, 64);
+        fix.speedAccMmS = readU4(payload, 68);
+        fix.headingAccDegE5 = readU4(payload, 72);
+        fix.fixType = payload[20];
+        fix.numSv = payload[23];
+        fix.validDate = (validFlags & 0x01U) != 0U;
+        fix.validTime = (validFlags & 0x02U) != 0U;
+        fix.gnssFixOk = (fixFlags & 0x01U) != 0U;
+        fix.invalidLlh = (flags3 & 0x0001U) != 0U;
+        fix.valid = isUsableFix(fix);
+
+        g_latestFix = fix;
+        g_hasFix = true;
+        g_present = true;
+        if (!g_streaming)
+        {
+            Debug::info(Messages::Log::kGpsNavPvtStreaming);
+        }
+        g_streaming = true;
+    }
@@ begin/reset stato @@
         g_started = true;
         g_present = false;
         g_configured = false;
+        g_streaming = false;
+        g_hasFix = false;
+        g_latestFix = {};
+        resetParser();
         Debug::info(Messages::Log::kGpsUartStarted);
         g_configured = detectAndConfigureSamM10Q();
         Debug::info(g_configured ? Messages::Log::kGpsConfigured : Messages::Log::kGpsConfigFailed);
@@ update parser @@
-        uint8_t drainedBytes = 0;
-        while (drainedBytes < UBX_DRAIN_CHUNK_BYTES && g_gpsSerial.available() > 0)
+        size_t parsedBytes = 0;
+        while (parsedBytes < UBX_PARSE_CHUNK_BYTES && g_gpsSerial.available() > 0)
         {
-            (void)g_gpsSerial.read();
-            ++drainedBytes;
+            processParserByte(static_cast<uint8_t>(g_gpsSerial.read()));
+            ++parsedBytes;
+        }
+
+        if (g_streaming && !hasFreshFixAt(millis()))
+        {
+            g_streaming = false;
+            Debug::info(Messages::Log::kGpsNavPvtTimeout);
         }
@@ nuove API @@
     bool configured()
     {
         return g_configured;
     }
+
+    bool streaming()
+    {
+        return hasFreshFixAt(millis());
+    }
+
+    bool readFix(GpsFixData& out)
+    {
+        if (!g_hasFix)
+        {
+            return false;
+        }
+
+        out = g_latestFix;
+        return true;
+    }
+
+    uint32_t fixAgeMs()
+    {
+        return g_hasFix ? (millis() - g_latestFix.receivedAtMs) : 0U;
+    }

diff --git a/src/messages.h b/src/messages.h
@@
         extern const char* const kGpsConfigured;
         // Messaggio emesso quando la configurazione runtime del SAM-M10Q non viene confermata.
         extern const char* const kGpsConfigFailed;
+        // Messaggio emesso quando il parser rileva stream UBX-NAV-PVT dal GPS.
+        extern const char* const kGpsNavPvtStreaming;
+        // Messaggio emesso quando il flusso NAV-PVT si interrompe oltre la soglia di freshness.
+        extern const char* const kGpsNavPvtTimeout;
         // Messaggio emesso quando l'inizializzazione BLE fallisce.
         extern const char* const kBleInitFailed;

diff --git a/src/messages.cpp b/src/messages.cpp
@@
         const char* const kGpsUartStarted = "Configurazione SAM-M10Q su UART GPS in corso";
         const char* const kGpsConfigured = "SAM-M10Q configurato: UBX-NAV-PVT 10 Hz, UART 115200, modello Automotive";
         const char* const kGpsConfigFailed = "ATTENZIONE: configurazione SAM-M10Q non confermata";
+        const char* const kGpsNavPvtStreaming = "SAM-M10Q: stream UBX-NAV-PVT rilevato";
+        const char* const kGpsNavPvtTimeout = "ATTENZIONE: stream UBX-NAV-PVT assente o fermo";
         const char* const kBleInitFailed = "ERRORE: inizializzazione BLE fallita";
         const char* const kBleAdvertisingStarted = "BLE pronto: advertising attiva";

diff --git a/src/inclinometro_esp32.ino b/src/inclinometro_esp32.ino
@@ void loop() @@
     CalibrationData calibrationBefore {};
     ReferenceData referenceSnapshot {};
     RuntimeState runtimeStateSnapshot {};
 
+    // Il parser GPS viene fatto avanzare subito, cosi lo status vede dati freschi nello stesso ciclo.
+    Gps::update();
+
     if (!lockState(pdMS_TO_TICKS(10)))
     {
         vTaskDelay(pdMS_TO_TICKS(1));
         return;
@@
-    Gps::update();
     vTaskDelay(pdMS_TO_TICKS(1));
 }

diff --git a/src/comm_shared.h b/src/comm_shared.h
@@
-static constexpr const char* COMM_PROTOCOL_VERSION = "4.8";
+static constexpr const char* COMM_PROTOCOL_VERSION = "4.9";
 static constexpr bool COMM_LIVE_INCLUDES_GPS = false;

diff --git a/src/comm_protocol.h b/src/comm_protocol.h
@@
-    // Serializza lo stato operativo complessivo del firmware senza metriche peak runtime,
-    // inclusa la presenza rilevata del modulo GPS opzionale e la capability live GPS.
+    // Serializza lo stato operativo complessivo del firmware senza metriche peak runtime,
+    // inclusa la presenza del modulo GPS, lo stato del parser NAV-PVT
+    // e la capability live GPS.
     String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId = "");

diff --git a/src/comm_protocol.cpp b/src/comm_protocol.cpp
@@ makeStatusJson @@
-    // Compone il quadro operativo completo usato per diagnostica e health check,
-    // inclusa la presenza del modulo GPS opzionale quando rilevato su UART
-    // e la dichiarazione sul fatto che il live includa o meno campi GPS.
+    // Compone il quadro operativo completo usato per diagnostica e health check,
+    // inclusa la presenza del modulo GPS opzionale quando rilevato su UART
+    // e lo stato del parser NAV-PVT, mantenendo il live privo di campi GPS.
     String makeStatusJson(const AppConfig& config, const CalibrationData& calibration, const ReferenceData& reference, const RuntimeState& runtimeState, const CommTransportRuntime& runtime, const String& requestId)
     {
         // Lo status unisce stato configurato, stato runtime essenziale e health del trasporto.
         String i2cAddress = "0x00";
+        GpsFixData gpsFix {};
+        const bool gpsHasFix = Gps::readFix(gpsFix);
+        const bool gpsStreaming = Gps::streaming();
@@
         payload += ",\"imu_ready\":";
         payload += runtimeState.imuReady ? "true" : "false";
         payload += ",\"gps_present\":";
         payload += Gps::present() ? "true" : "false";
+        payload += ",\"gps_configured\":";
+        payload += Gps::configured() ? "true" : "false";
+        payload += ",\"gps_streaming\":";
+        payload += gpsStreaming ? "true" : "false";
+        payload += ",\"gps_fix_ok\":";
+        payload += (gpsHasFix && gpsStreaming && gpsFix.valid) ? "true" : "false";
+        payload += ",\"gps_fix_type\":";
+        payload += String(static_cast<unsigned int>(gpsHasFix ? gpsFix.fixType : 0U));
+        payload += ",\"gps_num_sv\":";
+        payload += String(static_cast<unsigned int>(gpsHasFix ? gpsFix.numSv : 0U));
+        payload += ",\"gps_fix_age_ms\":";
+        payload += String(static_cast<unsigned long>(gpsHasFix ? Gps::fixAgeMs() : 0U));
         payload += ",\"live_includes_gps\":";
         payload += COMM_LIVE_INCLUDES_GPS ? "true" : "false";
         payload += ",\"i2c_address\":";

diff --git a/src/comm.h b/src/comm.h
@@
     // Invia lo snapshot runtime corrente al client BLE attivo, se presente.
     // Il payload live compact espone i valori istantanei consentiti dal protocollo,
-    // inclusi timestamp e temperatura della IMU.
+    // inclusi timestamp e temperatura della IMU.
+    // Lo stato del parser GPS resta confinato a info/status e non entra nel live.
     // Durante OTA il layer trasporto puo sospendere temporaneamente le notify live.
     void sendRuntimeState(const RuntimeState& runtimeState);

diff --git a/src/comm.cpp b/src/comm.cpp
@@
     // Propaga la telemetria runtime compact al trasporto senza esporre dettagli GATT.
     // Il payload resta limitato ai valori istantanei previsti dal protocollo,
-    // inclusi i nuovi metadati IMU del campione corrente.
+    // inclusi i nuovi metadati IMU del campione corrente.
+    // I dettagli diagnostici del parser GPS restano invece nel solo status BLE.
     // Il trasporto puo sospendere queste notify mentre un OTA e in corso.
     void sendRuntimeState(const RuntimeState& runtimeState)
     {

diff --git a/src/comm_transport.h b/src/comm_transport.h
@@
     // Fa avanzare il trasporto BLE usando il contesto applicativo corrente.
     void handleIncoming(const CommRequestContext& context, CommTransportRuntime& runtime);
     // Invia telemetria live al client BLE autenticato e sottoscritto,
     // compresi timestamp e temperatura IMU del campione corrente.
+    // L'eventuale stato del parser GPS viene invece letto via comandi info/status.
     // Durante un OTA in corso le notify live vengono sospese per lasciare banda al trasferimento.
     void sendRuntimeState(const RuntimeState& runtimeState);

diff --git a/src/comm_transport.cpp b/src/comm_transport.cpp
@@
-        // Il framing BLE resta invariato: cambia solo il contenuto del compact live.
+        // Il framing BLE resta invariato: cambia solo il contenuto del compact live,
+        // che continua a restare IMU-only anche se il parser GPS aggiorna lo status.
         notifyFramed(g_liveTxCharacteristic, CommProtocol::makeRuntimeStateJson(runtimeState));
     }
 }
```
