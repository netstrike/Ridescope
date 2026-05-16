#include "gps.h"

#include "config.h"
#include "debug.h"
#include "messages.h"

namespace
{
    constexpr uint8_t UBX_SYNC_CHAR_1 = 0xB5;
    constexpr uint8_t UBX_SYNC_CHAR_2 = 0x62;
    constexpr uint8_t UBX_CLASS_NAV = 0x01;
    constexpr uint8_t UBX_ID_NAV_PVT = 0x07;
    constexpr uint8_t UBX_CLASS_ACK = 0x05;
    constexpr uint8_t UBX_ID_ACK_ACK = 0x01;
    constexpr uint8_t UBX_ID_ACK_NAK = 0x00;
    constexpr uint8_t UBX_CLASS_CFG = 0x06;
    constexpr uint8_t UBX_ID_CFG_VALSET = 0x8A;
    constexpr uint8_t UBX_LAYER_RAM = 0x01;
    constexpr uint16_t UBX_NAV_PVT_PAYLOAD_LENGTH = 92;
    constexpr size_t UBX_PARSE_CHUNK_BYTES = 192;
    constexpr uint32_t GPS_CONFIG_ACK_TIMEOUT_MS = 350;
    constexpr uint32_t GPS_SIGNAL_RESTART_DELAY_MS = 500;

    constexpr uint32_t CFG_RATE_MEAS = 0x30210001;
    constexpr uint32_t CFG_RATE_NAV = 0x30210002;
    constexpr uint32_t CFG_RATE_TIMEREF = 0x20210003;
    constexpr uint32_t CFG_NAVSPG_DYNMODEL = 0x20110021;
    constexpr uint32_t CFG_UART1_BAUDRATE = 0x40520001;
    constexpr uint32_t CFG_UART1INPROT_UBX = 0x10730001;
    constexpr uint32_t CFG_UART1INPROT_NMEA = 0x10730002;
    constexpr uint32_t CFG_UART1OUTPROT_UBX = 0x10740001;
    constexpr uint32_t CFG_UART1OUTPROT_NMEA = 0x10740002;
    constexpr uint32_t CFG_MSGOUT_UBX_NAV_PVT_UART1 = 0x20910007;
    constexpr uint32_t CFG_SIGNAL_GPS_ENA = 0x1031001F;
    constexpr uint32_t CFG_SIGNAL_GPS_L1CA_ENA = 0x10310001;
    constexpr uint32_t CFG_SIGNAL_SBAS_ENA = 0x10310020;
    constexpr uint32_t CFG_SIGNAL_SBAS_L1CA_ENA = 0x10310005;
    constexpr uint32_t CFG_SIGNAL_GAL_ENA = 0x10310021;
    constexpr uint32_t CFG_SIGNAL_GAL_E1_ENA = 0x10310007;
    constexpr uint32_t CFG_SIGNAL_BDS_ENA = 0x10310022;
    constexpr uint32_t CFG_SIGNAL_BDS_B1_ENA = 0x1031000D;
    constexpr uint32_t CFG_SIGNAL_BDS_B1C_ENA = 0x1031000F;
    constexpr uint32_t CFG_SIGNAL_QZSS_ENA = 0x10310024;
    constexpr uint32_t CFG_SIGNAL_QZSS_L1CA_ENA = 0x10310012;
    constexpr uint32_t CFG_SIGNAL_QZSS_L1S_ENA = 0x10310014;
    constexpr uint32_t CFG_SIGNAL_GLO_ENA = 0x10310025;
    constexpr uint32_t CFG_SIGNAL_GLO_L1_ENA = 0x10310018;

    constexpr uint8_t RATE_TIMEREF_GPS = 1;

    enum class ConfigValueType : uint8_t
    {
        Bool,
        U1,
        U2,
        U4
    };

    struct ConfigItem
    {
        uint32_t key = 0;
        ConfigValueType type = ConfigValueType::Bool;
        uint32_t value = 0;
    };

    struct UbxParserState
    {
        uint8_t state = 0;
        uint8_t messageClass = 0;
        uint8_t messageId = 0;
        uint16_t payloadLength = 0;
        uint16_t payloadIndex = 0;
        uint8_t payload[UBX_NAV_PVT_PAYLOAD_LENGTH] = {};
        uint8_t checksumA = 0;
        uint8_t checksumB = 0;
        uint8_t computedA = 0;
        uint8_t computedB = 0;
    };

    HardwareSerial g_gpsSerial(1);
    bool g_started = false;
    bool g_present = false;
    bool g_configured = false;
    bool g_streaming = false;
    bool g_hasFix = false;
    GpsFixData g_latestFix {};
    UbxParserState g_parser {};

    void appendU1(uint8_t* buffer, size_t& offset, uint8_t value)
    {
        buffer[offset++] = value;
    }

    void appendU2(uint8_t* buffer, size_t& offset, uint16_t value)
    {
        buffer[offset++] = static_cast<uint8_t>(value & 0xFFU);
        buffer[offset++] = static_cast<uint8_t>((value >> 8U) & 0xFFU);
    }

    void appendU4(uint8_t* buffer, size_t& offset, uint32_t value)
    {
        buffer[offset++] = static_cast<uint8_t>(value & 0xFFU);
        buffer[offset++] = static_cast<uint8_t>((value >> 8U) & 0xFFU);
        buffer[offset++] = static_cast<uint8_t>((value >> 16U) & 0xFFU);
        buffer[offset++] = static_cast<uint8_t>((value >> 24U) & 0xFFU);
    }

    uint16_t readU2(const uint8_t* buffer, size_t offset)
    {
        return static_cast<uint16_t>(buffer[offset]) |
               (static_cast<uint16_t>(buffer[offset + 1U]) << 8U);
    }

    uint32_t readU4(const uint8_t* buffer, size_t offset)
    {
        return static_cast<uint32_t>(buffer[offset]) |
               (static_cast<uint32_t>(buffer[offset + 1U]) << 8U) |
               (static_cast<uint32_t>(buffer[offset + 2U]) << 16U) |
               (static_cast<uint32_t>(buffer[offset + 3U]) << 24U);
    }

    int32_t readI4(const uint8_t* buffer, size_t offset)
    {
        return static_cast<int32_t>(readU4(buffer, offset));
    }

    void appendConfigItem(uint8_t* buffer, size_t& offset, const ConfigItem& item)
    {
        appendU4(buffer, offset, item.key);
        switch (item.type)
        {
            case ConfigValueType::Bool:
            case ConfigValueType::U1:
                appendU1(buffer, offset, static_cast<uint8_t>(item.value & 0xFFU));
                break;
            case ConfigValueType::U2:
                appendU2(buffer, offset, static_cast<uint16_t>(item.value & 0xFFFFU));
                break;
            case ConfigValueType::U4:
            default:
                appendU4(buffer, offset, item.value);
                break;
        }
    }

    void drainRx(uint32_t quietWindowMs = 25U)
    {
        const uint32_t deadline = millis() + quietWindowMs;
        while (static_cast<int32_t>(millis() - deadline) < 0)
        {
            while (g_gpsSerial.available() > 0)
            {
                (void)g_gpsSerial.read();
            }
            delay(1);
        }
    }

    bool hasFreshFixAt(uint32_t nowMs)
    {
        return g_hasFix && (nowMs - g_latestFix.receivedAtMs) <= GPS_FIX_STALE_TIMEOUT_MS;
    }

    bool isUsableFix(const GpsFixData& fix)
    {
        return fix.gnssFixOk &&
               !fix.invalidLlh &&
               (fix.fixType == 2U || fix.fixType == 3U || fix.fixType == 4U);
    }

    void resetParser()
    {
        g_parser = {};
    }

    void decodeNavPvt(const uint8_t* payload)
    {
        GpsFixData fix {};
        const uint8_t validFlags = payload[11];
        const uint8_t fixFlags = payload[21];
        const uint16_t flags3 = readU2(payload, 78);

        fix.iTowMs = readU4(payload, 0);
        fix.receivedAtMs = millis();
        fix.longitudeDegE7 = readI4(payload, 24);
        fix.latitudeDegE7 = readI4(payload, 28);
        fix.heightMslMm = readI4(payload, 36);
        fix.horizontalAccMm = readU4(payload, 40);
        fix.velNorthMmS = readI4(payload, 48);
        fix.velEastMmS = readI4(payload, 52);
        fix.velDownMmS = readI4(payload, 56);
        fix.groundSpeedMmS = readI4(payload, 60);
        fix.headingMotionDegE5 = readI4(payload, 64);
        fix.speedAccMmS = readU4(payload, 68);
        fix.headingAccDegE5 = readU4(payload, 72);
        fix.fixType = payload[20];
        fix.numSv = payload[23];
        fix.validDate = (validFlags & 0x01U) != 0U;
        fix.validTime = (validFlags & 0x02U) != 0U;
        fix.gnssFixOk = (fixFlags & 0x01U) != 0U;
        fix.invalidLlh = (flags3 & 0x0001U) != 0U;
        fix.valid = isUsableFix(fix);

        g_latestFix = fix;
        g_hasFix = true;
        g_present = true;
        if (!g_streaming)
        {
            Debug::info(Messages::Log::kGpsNavPvtStreaming);
        }
        g_streaming = true;
    }

    void handleUbxFrame(uint8_t messageClass, uint8_t messageId, const uint8_t* payload, uint16_t payloadLength)
    {
        g_present = true;
        if (messageClass == UBX_CLASS_NAV &&
            messageId == UBX_ID_NAV_PVT &&
            payloadLength == UBX_NAV_PVT_PAYLOAD_LENGTH)
        {
            decodeNavPvt(payload);
        }
    }

    void processParserByte(uint8_t byteValue)
    {
        switch (g_parser.state)
        {
            case 0:
                g_parser.state = (byteValue == UBX_SYNC_CHAR_1) ? 1 : 0;
                break;
            case 1:
                if (byteValue == UBX_SYNC_CHAR_2)
                {
                    g_parser.state = 2;
                }
                else
                {
                    g_parser.state = (byteValue == UBX_SYNC_CHAR_1) ? 1 : 0;
                }
                break;
            case 2:
                g_parser.messageClass = byteValue;
                g_parser.payloadLength = 0;
                g_parser.payloadIndex = 0;
                g_parser.computedA = byteValue;
                g_parser.computedB = g_parser.computedA;
                g_parser.state = 3;
                break;
            case 3:
                g_parser.messageId = byteValue;
                g_parser.computedA = static_cast<uint8_t>(g_parser.computedA + byteValue);
                g_parser.computedB = static_cast<uint8_t>(g_parser.computedB + g_parser.computedA);
                g_parser.state = 4;
                break;
            case 4:
                g_parser.payloadLength = byteValue;
                g_parser.computedA = static_cast<uint8_t>(g_parser.computedA + byteValue);
                g_parser.computedB = static_cast<uint8_t>(g_parser.computedB + g_parser.computedA);
                g_parser.state = 5;
                break;
            case 5:
                g_parser.payloadLength |= static_cast<uint16_t>(byteValue) << 8U;
                g_parser.computedA = static_cast<uint8_t>(g_parser.computedA + byteValue);
                g_parser.computedB = static_cast<uint8_t>(g_parser.computedB + g_parser.computedA);
                g_parser.payloadIndex = 0;
                g_parser.state = (g_parser.payloadLength == 0U) ? 7 : 6;
                break;
            case 6:
                if (g_parser.payloadIndex < sizeof(g_parser.payload))
                {
                    g_parser.payload[g_parser.payloadIndex] = byteValue;
                }
                ++g_parser.payloadIndex;
                g_parser.computedA = static_cast<uint8_t>(g_parser.computedA + byteValue);
                g_parser.computedB = static_cast<uint8_t>(g_parser.computedB + g_parser.computedA);
                if (g_parser.payloadIndex >= g_parser.payloadLength)
                {
                    g_parser.state = 7;
                }
                break;
            case 7:
                g_parser.checksumA = byteValue;
                g_parser.state = 8;
                break;
            case 8:
            {
                g_parser.checksumB = byteValue;
                const bool checksumOk = g_parser.checksumA == g_parser.computedA &&
                                        g_parser.checksumB == g_parser.computedB;
                if (checksumOk)
                {
                    handleUbxFrame(g_parser.messageClass, g_parser.messageId, g_parser.payload, g_parser.payloadLength);
                }
                resetParser();
                break;
            }
            default:
                resetParser();
                break;
        }
    }

    bool sendUbxFrame(uint8_t messageClass, uint8_t messageId, const uint8_t* payload, size_t payloadLength)
    {
        uint8_t frame[160] = {};
        if (payloadLength > (sizeof(frame) - 8U))
        {
            return false;
        }

        size_t offset = 0;
        frame[offset++] = UBX_SYNC_CHAR_1;
        frame[offset++] = UBX_SYNC_CHAR_2;
        frame[offset++] = messageClass;
        frame[offset++] = messageId;
        appendU2(frame, offset, static_cast<uint16_t>(payloadLength));
        for (size_t i = 0; i < payloadLength; ++i)
        {
            frame[offset++] = payload[i];
        }

        uint8_t checksumA = 0;
        uint8_t checksumB = 0;
        for (size_t i = 2; i < offset; ++i)
        {
            checksumA = static_cast<uint8_t>(checksumA + frame[i]);
            checksumB = static_cast<uint8_t>(checksumB + checksumA);
        }

        frame[offset++] = checksumA;
        frame[offset++] = checksumB;
        g_gpsSerial.write(frame, offset);
        g_gpsSerial.flush();
        return true;
    }

    bool waitForAck(uint8_t expectedClass, uint8_t expectedId, uint32_t timeoutMs)
    {
        uint8_t state = 0;
        uint8_t messageClass = 0;
        uint8_t messageId = 0;
        uint16_t payloadLength = 0;
        uint16_t payloadIndex = 0;
        uint8_t payload[2] = {};
        uint8_t checksumA = 0;
        uint8_t checksumB = 0;
        uint8_t computedA = 0;
        uint8_t computedB = 0;
        const uint32_t deadline = millis() + timeoutMs;

        while (static_cast<int32_t>(millis() - deadline) < 0)
        {
            while (g_gpsSerial.available() > 0)
            {
                const uint8_t byteValue = static_cast<uint8_t>(g_gpsSerial.read());
                switch (state)
                {
                    case 0:
                        state = (byteValue == UBX_SYNC_CHAR_1) ? 1 : 0;
                        break;
                    case 1:
                        state = (byteValue == UBX_SYNC_CHAR_2) ? 2 : 0;
                        break;
                    case 2:
                        messageClass = byteValue;
                        payloadLength = 0;
                        payloadIndex = 0;
                        computedA = byteValue;
                        computedB = computedA;
                        state = 3;
                        break;
                    case 3:
                        messageId = byteValue;
                        computedA = static_cast<uint8_t>(computedA + byteValue);
                        computedB = static_cast<uint8_t>(computedB + computedA);
                        state = 4;
                        break;
                    case 4:
                        payloadLength = byteValue;
                        computedA = static_cast<uint8_t>(computedA + byteValue);
                        computedB = static_cast<uint8_t>(computedB + computedA);
                        state = 5;
                        break;
                    case 5:
                        payloadLength |= static_cast<uint16_t>(byteValue) << 8U;
                        computedA = static_cast<uint8_t>(computedA + byteValue);
                        computedB = static_cast<uint8_t>(computedB + computedA);
                        payloadIndex = 0;
                        state = (payloadLength == 0U) ? 7 : 6;
                        break;
                    case 6:
                        if (payloadIndex < sizeof(payload))
                        {
                            payload[payloadIndex] = byteValue;
                        }
                        ++payloadIndex;
                        computedA = static_cast<uint8_t>(computedA + byteValue);
                        computedB = static_cast<uint8_t>(computedB + computedA);
                        if (payloadIndex >= payloadLength)
                        {
                            state = 7;
                        }
                        break;
                    case 7:
                        checksumA = byteValue;
                        state = 8;
                        break;
                    case 8:
                    {
                        checksumB = byteValue;
                        const bool checksumOk = checksumA == computedA && checksumB == computedB;
                        const bool ackForMessage = checksumOk &&
                                                   messageClass == UBX_CLASS_ACK &&
                                                   payloadLength >= 2U &&
                                                   payload[0] == expectedClass &&
                                                   payload[1] == expectedId;
                        state = 0;
                        if (ackForMessage)
                        {
                            return messageId == UBX_ID_ACK_ACK;
                        }
                        break;
                    }
                    default:
                        state = 0;
                        break;
                }
            }
            delay(1);
        }

        return false;
    }

    template <size_t N>
    bool sendValset(const ConfigItem (&items)[N], bool waitAck = true, uint32_t ackTimeoutMs = GPS_CONFIG_ACK_TIMEOUT_MS)
    {
        uint8_t payload[4U + (N * 8U)] = {};
        size_t offset = 0;
        appendU1(payload, offset, 0x00);
        appendU1(payload, offset, UBX_LAYER_RAM);
        appendU1(payload, offset, 0x00);
        appendU1(payload, offset, 0x00);
        for (const ConfigItem& item : items)
        {
            appendConfigItem(payload, offset, item);
        }

        drainRx();
        if (!sendUbxFrame(UBX_CLASS_CFG, UBX_ID_CFG_VALSET, payload, offset))
        {
            return false;
        }

        if (!waitAck)
        {
            return true;
        }

        return waitForAck(UBX_CLASS_CFG, UBX_ID_CFG_VALSET, ackTimeoutMs);
    }

    bool configureReceiverAtCurrentBaud()
    {
        const ConfigItem protocolConfig[] = {
            {CFG_UART1INPROT_UBX, ConfigValueType::Bool, 1U},
            {CFG_UART1INPROT_NMEA, ConfigValueType::Bool, 0U},
            {CFG_UART1OUTPROT_UBX, ConfigValueType::Bool, 1U},
            {CFG_UART1OUTPROT_NMEA, ConfigValueType::Bool, 0U},
        };
        if (!sendValset(protocolConfig))
        {
            return false;
        }

        const ConfigItem navigationConfig[] = {
            {CFG_RATE_MEAS, ConfigValueType::U2, GPS_NAV_MEAS_RATE_MS},
            {CFG_RATE_NAV, ConfigValueType::U2, 1U},
            {CFG_RATE_TIMEREF, ConfigValueType::U1, RATE_TIMEREF_GPS},
            {CFG_NAVSPG_DYNMODEL, ConfigValueType::U1, GPS_NAV_DYNAMIC_MODEL},
            {CFG_MSGOUT_UBX_NAV_PVT_UART1, ConfigValueType::U1, GPS_NAV_PVT_RATE},
        };
        if (!sendValset(navigationConfig))
        {
            return false;
        }

        const ConfigItem signalConfig[] = {
            {CFG_SIGNAL_GPS_ENA, ConfigValueType::Bool, 1U},
            {CFG_SIGNAL_GPS_L1CA_ENA, ConfigValueType::Bool, 1U},
            {CFG_SIGNAL_SBAS_ENA, ConfigValueType::Bool, 1U},
            {CFG_SIGNAL_SBAS_L1CA_ENA, ConfigValueType::Bool, 1U},
            {CFG_SIGNAL_GAL_ENA, ConfigValueType::Bool, 1U},
            {CFG_SIGNAL_GAL_E1_ENA, ConfigValueType::Bool, 1U},
            {CFG_SIGNAL_BDS_ENA, ConfigValueType::Bool, 0U},
            {CFG_SIGNAL_BDS_B1_ENA, ConfigValueType::Bool, 0U},
            {CFG_SIGNAL_BDS_B1C_ENA, ConfigValueType::Bool, 0U},
            {CFG_SIGNAL_QZSS_ENA, ConfigValueType::Bool, 1U},
            {CFG_SIGNAL_QZSS_L1CA_ENA, ConfigValueType::Bool, 1U},
            {CFG_SIGNAL_QZSS_L1S_ENA, ConfigValueType::Bool, 0U},
            {CFG_SIGNAL_GLO_ENA, ConfigValueType::Bool, 0U},
            {CFG_SIGNAL_GLO_L1_ENA, ConfigValueType::Bool, 0U},
        };
        if (!sendValset(signalConfig))
        {
            return false;
        }

        delay(GPS_SIGNAL_RESTART_DELAY_MS);

        const ConfigItem baudrateConfig[] = {
            {CFG_UART1_BAUDRATE, ConfigValueType::U4, GPS_UART_BAUDRATE},
        };
        if (!sendValset(baudrateConfig, false))
        {
            return false;
        }

        delay(50);
        g_gpsSerial.end();
        delay(20);
        g_gpsSerial.begin(GPS_UART_BAUDRATE, SERIAL_8N1, GPS_UART_RX_PIN, GPS_UART_TX_PIN);

        const ConfigItem probeConfig[] = {
            {CFG_UART1OUTPROT_UBX, ConfigValueType::Bool, 1U},
        };
        return sendValset(probeConfig);
    }

    bool detectAndConfigureSamM10Q()
    {
        const uint32_t candidateBaudrates[] = {
            GPS_UART_BAUDRATE,
            GPS_UART_FACTORY_BAUDRATE,
            GPS_UART_LEGACY_FALLBACK_BAUDRATE,
        };

        const ConfigItem probeConfig[] = {
            {CFG_UART1INPROT_UBX, ConfigValueType::Bool, 1U},
        };

        for (uint32_t candidateBaudrate : candidateBaudrates)
        {
            g_gpsSerial.begin(candidateBaudrate, SERIAL_8N1, GPS_UART_RX_PIN, GPS_UART_TX_PIN);
            delay(30);
            if (!sendValset(probeConfig))
            {
                g_gpsSerial.end();
                delay(20);
                continue;
            }

            g_present = true;
            return configureReceiverAtCurrentBaud();
        }

        g_gpsSerial.begin(GPS_UART_BAUDRATE, SERIAL_8N1, GPS_UART_RX_PIN, GPS_UART_TX_PIN);
        return false;
    }
}

namespace Gps
{
    void begin()
    {
        if (!GPS_UART_ENABLED || g_started)
        {
            return;
        }

        g_started = true;
        g_present = false;
        g_configured = false;
        g_streaming = false;
        g_hasFix = false;
        g_latestFix = {};
        resetParser();
        Debug::info(Messages::Log::kGpsUartStarted);
        g_configured = detectAndConfigureSamM10Q();
        Debug::info(g_configured ? Messages::Log::kGpsConfigured : Messages::Log::kGpsConfigFailed);
    }

    void update()
    {
        if (!g_started)
        {
            return;
        }

        size_t parsedBytes = 0;
        while (parsedBytes < UBX_PARSE_CHUNK_BYTES && g_gpsSerial.available() > 0)
        {
            processParserByte(static_cast<uint8_t>(g_gpsSerial.read()));
            ++parsedBytes;
        }

        if (g_streaming && !hasFreshFixAt(millis()))
        {
            g_streaming = false;
            Debug::info(Messages::Log::kGpsNavPvtTimeout);
        }
    }

    bool enabled()
    {
        return GPS_UART_ENABLED;
    }

    bool present()
    {
        return g_present;
    }

    bool configured()
    {
        return g_configured;
    }

    bool streaming()
    {
        return hasFreshFixAt(millis());
    }

    bool readFix(GpsFixData& out)
    {
        if (!g_hasFix)
        {
            return false;
        }

        out = g_latestFix;
        return true;
    }

    uint32_t fixAgeMs()
    {
        return g_hasFix ? (millis() - g_latestFix.receivedAtMs) : 0U;
    }

    HardwareSerial& serial()
    {
        return g_gpsSerial;
    }
}
