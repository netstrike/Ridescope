#include "comm_transport.h"

#include "comm_dispatch.h"
#include "comm_json.h"
#include "comm_protocol.h"
#include "config.h"
#include "debug.h"
#include "messages.h"
#include "sensors.h"
#include "status_led.h"
#include "storage.h"

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLESecurity.h>
#include <Update.h>

namespace
{
    static constexpr size_t kBleMaxCommandBytes = 512;
    static constexpr size_t kBleMaxOtaChunkBytes = 512;
    static constexpr size_t kBleOtaQueueCapacity = 8;

    struct PendingCommand
    {
        bool ready = false;
        size_t length = 0;
        char data[kBleMaxCommandBytes + 1] = {};
    };

    struct PendingOtaChunk
    {
        size_t length = 0;
        uint8_t data[kBleMaxOtaChunkBytes] = {};
    };

    BLEServer* g_bleServer = nullptr;
    BLECharacteristic* g_commandRxCharacteristic = nullptr;
    BLECharacteristic* g_responseTxCharacteristic = nullptr;
    BLECharacteristic* g_liveTxCharacteristic = nullptr;
    BLECharacteristic* g_otaDataRxCharacteristic = nullptr;

    CommRequestContext g_context;
    CommTransportRuntime* g_runtime = nullptr;
    PendingCommand g_pendingCommand;
    PendingOtaChunk g_otaQueue[kBleOtaQueueCapacity];
    portMUX_TYPE g_transportMux = portMUX_INITIALIZER_UNLOCKED;

    size_t g_otaQueueHead = 0;
    size_t g_otaQueueTail = 0;
    size_t g_otaQueueCount = 0;
    bool g_commandOverflow = false;
    bool g_otaQueueOverflow = false;
    bool g_liveStreamLogged = false;
    uint32_t g_rebootAtMs = 0;

    size_t chunkLimit()
    {
        if (g_runtime == nullptr || g_runtime->bleMtu <= 3U)
        {
            return 20U;
        }
        return static_cast<size_t>(g_runtime->bleMtu - 3U);
    }

    void clearSubscriptions()
    {
        if (g_runtime == nullptr)
        {
            return;
        }

        g_runtime->bleResponseSubscribed = false;
        g_runtime->bleLiveSubscribed = false;
        g_liveStreamLogged = false;
    }

    void clearOtaQueue()
    {
        portENTER_CRITICAL(&g_transportMux);
        g_otaQueueHead = 0;
        g_otaQueueTail = 0;
        g_otaQueueCount = 0;
        g_otaQueueOverflow = false;
        portEXIT_CRITICAL(&g_transportMux);
    }

    bool enqueueCommand(const uint8_t* data, size_t length)
    {
        if (data == nullptr || length == 0U || length > kBleMaxCommandBytes)
        {
            return false;
        }

        bool accepted = false;
        portENTER_CRITICAL(&g_transportMux);
        if (!g_pendingCommand.ready)
        {
            memcpy(g_pendingCommand.data, data, length);
            g_pendingCommand.data[length] = '\0';
            g_pendingCommand.length = length;
            g_pendingCommand.ready = true;
            accepted = true;
        }
        else
        {
            g_commandOverflow = true;
        }
        portEXIT_CRITICAL(&g_transportMux);
        return accepted;
    }

    bool popCommand(String& out)
    {
        char buffer[kBleMaxCommandBytes + 1] = {};
        size_t length = 0;

        portENTER_CRITICAL(&g_transportMux);
        if (!g_pendingCommand.ready)
        {
            portEXIT_CRITICAL(&g_transportMux);
            return false;
        }

        length = g_pendingCommand.length;
        memcpy(buffer, g_pendingCommand.data, length + 1U);
        g_pendingCommand.ready = false;
        g_pendingCommand.length = 0;
        g_pendingCommand.data[0] = '\0';
        portEXIT_CRITICAL(&g_transportMux);

        out = String(buffer);
        return true;
    }

    bool takeCommandOverflowFlag()
    {
        bool overflow = false;
        portENTER_CRITICAL(&g_transportMux);
        overflow = g_commandOverflow;
        g_commandOverflow = false;
        portEXIT_CRITICAL(&g_transportMux);
        return overflow;
    }

    bool enqueueOtaChunk(const uint8_t* data, size_t length)
    {
        if (data == nullptr || length == 0U || length > kBleMaxOtaChunkBytes)
        {
            return false;
        }

        bool accepted = false;
        portENTER_CRITICAL(&g_transportMux);
        if (g_otaQueueCount < kBleOtaQueueCapacity)
        {
            PendingOtaChunk& slot = g_otaQueue[g_otaQueueTail];
            slot.length = length;
            memcpy(slot.data, data, length);
            g_otaQueueTail = (g_otaQueueTail + 1U) % kBleOtaQueueCapacity;
            ++g_otaQueueCount;
            accepted = true;
        }
        else
        {
            g_otaQueueOverflow = true;
        }
        portEXIT_CRITICAL(&g_transportMux);
        return accepted;
    }

    bool popOtaChunk(PendingOtaChunk& out)
    {
        portENTER_CRITICAL(&g_transportMux);
        if (g_otaQueueCount == 0U)
        {
            portEXIT_CRITICAL(&g_transportMux);
            return false;
        }

        out = g_otaQueue[g_otaQueueHead];
        g_otaQueue[g_otaQueueHead].length = 0;
        g_otaQueueHead = (g_otaQueueHead + 1U) % kBleOtaQueueCapacity;
        --g_otaQueueCount;
        portEXIT_CRITICAL(&g_transportMux);
        return true;
    }

    bool takeOtaOverflowFlag()
    {
        bool overflow = false;
        portENTER_CRITICAL(&g_transportMux);
        overflow = g_otaQueueOverflow;
        g_otaQueueOverflow = false;
        portEXIT_CRITICAL(&g_transportMux);
        return overflow;
    }

    void flagOtaQueueOverflow()
    {
        portENTER_CRITICAL(&g_transportMux);
        g_otaQueueOverflow = true;
        portEXIT_CRITICAL(&g_transportMux);
    }

    bool responseChannelReady()
    {
        return g_runtime != nullptr &&
               g_runtime->bleReady &&
               g_runtime->bleClientConnected &&
               g_runtime->bleClientAuthenticated &&
               g_runtime->bleResponseSubscribed &&
               g_responseTxCharacteristic != nullptr;
    }

    bool liveChannelReady()
    {
        return g_runtime != nullptr &&
               g_runtime->bleReady &&
               g_runtime->bleClientConnected &&
               g_runtime->bleClientAuthenticated &&
               g_runtime->bleLiveSubscribed &&
               g_liveTxCharacteristic != nullptr;
    }

    void notifyFramed(BLECharacteristic* characteristic, const String& payload)
    {
        if (characteristic == nullptr || g_runtime == nullptr)
        {
            return;
        }

        String framed = payload;
        framed += '\n';

        const size_t maxChunkBytes = chunkLimit();
        size_t offset = 0;
        while (offset < framed.length())
        {
            const size_t remaining = framed.length() - offset;
            const size_t currentChunk = remaining < maxChunkBytes ? remaining : maxChunkBytes;
            characteristic->setValue(reinterpret_cast<const uint8_t*>(framed.c_str() + offset), currentChunk);
            characteristic->notify();
            offset += currentChunk;
        }
    }

    void sendResponsePayload(const String& payload)
    {
        if (!responseChannelReady())
        {
            return;
        }

        notifyFramed(g_responseTxCharacteristic, payload);
    }

    String readRequestId(const JsonObjectConst& root)
    {
        String requestId;
        (void)CommJson::readString(root, "id", requestId);
        return requestId;
    }

    void stopOtaSession(const String& errorMessage, bool aborted)
    {
        clearOtaQueue();

        if (g_runtime == nullptr)
        {
            return;
        }

        if (g_runtime->updateInProgress)
        {
            Update.abort();
        }

        g_runtime->updateInProgress = false;
        g_runtime->updateSuccess = false;
        g_runtime->rebootScheduled = false;
        if (errorMessage.length() > 0)
        {
            g_runtime->lastError = errorMessage;
        }

        if (aborted)
        {
            Debug::info(Messages::Log::kBleOtaAborted);
        }
    }

    bool beginOtaSession(size_t expectedSize)
    {
        clearOtaQueue();

        if (g_runtime == nullptr)
        {
            return false;
        }

        g_runtime->updateInProgress = true;
        g_runtime->updateSuccess = false;
        g_runtime->rebootScheduled = false;
        g_runtime->progressPercent = 0;
        g_runtime->expectedSize = expectedSize;
        g_runtime->writtenBytes = 0;
        g_runtime->lastError = "";

        if (!Update.begin(expectedSize))
        {
            g_runtime->updateInProgress = false;
            g_runtime->lastError = Update.errorString();
            return false;
        }

        Debug::info(Messages::Log::kBleOtaStarted);
        return true;
    }

    bool applyOtaChunk(PendingOtaChunk& chunk)
    {
        if (g_runtime == nullptr || !g_runtime->updateInProgress)
        {
            stopOtaSession(Messages::Protocol::kOtaNonInCorso, false);
            return false;
        }

        if ((g_runtime->writtenBytes + chunk.length) > g_runtime->expectedSize)
        {
            stopOtaSession(Messages::Protocol::kOtaChunkFuoriRange, false);
            return false;
        }

        if (Update.write(chunk.data, chunk.length) != chunk.length)
        {
            String updateError = Update.errorString();
            if (updateError.length() == 0)
            {
                updateError = Messages::Protocol::kOtaWriteFallita;
            }
            stopOtaSession(updateError, false);
            return false;
        }

        g_runtime->writtenBytes += chunk.length;
        if (g_runtime->expectedSize > 0U)
        {
            const size_t writtenPercent = (g_runtime->writtenBytes * 100U) / g_runtime->expectedSize;
            g_runtime->progressPercent = static_cast<uint8_t>(writtenPercent > 100U ? 100U : writtenPercent);
        }

        return true;
    }

    bool completeOtaSession()
    {
        if (g_runtime == nullptr || !g_runtime->updateInProgress)
        {
            return false;
        }

        if (!Update.end(true))
        {
            g_runtime->updateInProgress = false;
            g_runtime->updateSuccess = false;
            g_runtime->lastError = Update.errorString();
            return false;
        }

        g_runtime->updateInProgress = false;
        g_runtime->updateSuccess = true;
        g_runtime->rebootScheduled = true;
        g_runtime->progressPercent = 100;
        g_runtime->writtenBytes = g_runtime->expectedSize;
        g_runtime->lastError = "";
        g_rebootAtMs = millis() + BLE_OTA_REBOOT_DELAY_MS;
        Debug::info(Messages::Log::kBleOtaCompleted);
        return true;
    }

    void processQueuedOtaChunks()
    {
        if (takeOtaOverflowFlag())
        {
            stopOtaSession(Messages::Protocol::kOtaBufferSaturo, true);
            return;
        }

        PendingOtaChunk chunk;
        while (popOtaChunk(chunk))
        {
            if (!applyOtaChunk(chunk))
            {
                return;
            }
        }
    }

    String handleCalibrationRunCommand(const JsonObjectConst& root, const String& requestId)
    {
        String errorMessage;
        uint32_t samples = 300;
        uint32_t delayMs = 5;

        JsonFieldResult result = CommJson::readUInt(root, "samples", samples);
        if (CommJson::fieldInvalid(result, errorMessage, "samples", Messages::JsonTypes::kInteroSenzaSegno))
        {
            return CommProtocol::makeErrorJson(requestId, "run_calibration", errorMessage);
        }

        result = CommJson::readUInt(root, "delay_ms", delayMs);
        if (CommJson::fieldInvalid(result, errorMessage, "delay_ms", Messages::JsonTypes::kInteroSenzaSegno))
        {
            return CommProtocol::makeErrorJson(requestId, "run_calibration", errorMessage);
        }

        if (samples == 0U)
        {
            return CommProtocol::makeErrorJson(requestId, "run_calibration", Messages::Protocol::samplesMaggioreDiZero());
        }

        if (samples > 65535U)
        {
            return CommProtocol::makeErrorJson(requestId, "run_calibration", Messages::Protocol::samplesMax65535());
        }

        if (delayMs > 65535U)
        {
            return CommProtocol::makeErrorJson(requestId, "run_calibration", Messages::Protocol::delayMsMax65535());
        }

        if (!Sensors::startCalibrationStill(static_cast<uint16_t>(samples), static_cast<uint16_t>(delayMs)))
        {
            return CommProtocol::makeErrorJson(requestId, "run_calibration", Messages::Protocol::kCalibrazioneNonAvviata);
        }

        return CommProtocol::buildEnvelope("response", requestId, Messages::Protocol::kStatoAccettato, "\"cmd\":\"run_calibration\"");
    }

    String handleSetZeroCommand(const JsonObjectConst& root, const String& requestId)
    {
        if (g_context.reference == nullptr || g_context.runtimeState == nullptr)
        {
            return CommProtocol::makeErrorJson(requestId, "set_zero", Messages::Protocol::kComandoSconosciuto);
        }

        String errorMessage;
        String axes = "both";
        JsonFieldResult result = CommJson::readString(root, "axes", axes);
        if (CommJson::fieldInvalid(result, errorMessage, "axes", Messages::JsonTypes::kStringa))
        {
            return CommProtocol::makeErrorJson(requestId, "set_zero", errorMessage);
        }

        axes.toLowerCase();
        if (axes == "roll" || axes == "both")
        {
            g_context.reference->rollZeroDeg = g_context.runtimeState->rawRollDeg;
            g_context.reference->valid = true;
        }
        if (axes == "pitch" || axes == "both")
        {
            g_context.reference->pitchZeroDeg = g_context.runtimeState->rawPitchDeg;
            g_context.reference->valid = true;
        }
        if (axes != "roll" && axes != "pitch" && axes != "both")
        {
            return CommProtocol::makeErrorJson(requestId, "set_zero", Messages::Protocol::assiZeroNonValidi());
        }

        Storage::saveReference(*g_context.reference);
        return CommProtocol::makeReferenceJson(*g_context.reference, requestId);
    }

    String dispatchCommand(const String& request)
    {
        JsonDocument doc;
        JsonObjectConst root;
        String errorMessage;
        if (!CommJson::parseObject(request, doc, root, errorMessage, false))
        {
            return CommProtocol::makeErrorJson("", "unknown", errorMessage);
        }

        String command;
        if (CommJson::readString(root, "cmd", command) != JsonFieldResult::Ok)
        {
            return CommProtocol::makeErrorJson(readRequestId(root), "unknown", Messages::Protocol::campoObbligatorioStringa("cmd"));
        }

        const String requestId = readRequestId(root);
        command.toLowerCase();

        if (command == "ping")
        {
            return CommProtocol::buildEnvelope("pong", requestId, Messages::Protocol::kStatoOk, "");
        }

        if (command == "get_info")
        {
            return CommProtocol::makeInfoJson(requestId);
        }

        if (command == "get_config")
        {
            return CommProtocol::makeConfigJson(*g_context.config, *g_runtime, requestId);
        }

        if (command == "set_config")
        {
            AppConfig nextConfig = *g_context.config;
            if (!CommDispatch::applyConfigFromJson(root, nextConfig, errorMessage))
            {
                return CommProtocol::makeErrorJson(requestId, command, errorMessage);
            }

            *g_context.config = nextConfig;
            Storage::saveConfig(*g_context.config);
            return CommProtocol::makeConfigJson(*g_context.config, *g_runtime, requestId);
        }

        if (command == "get_status")
        {
            return CommProtocol::makeStatusJson(*g_context.config, *g_context.calibration, *g_context.reference, *g_context.runtimeState, *g_runtime, requestId);
        }

        if (command == "get_live")
        {
            return CommProtocol::makeRuntimeStateJson(*g_context.runtimeState);
        }

        if (command == "get_calibration")
        {
            return CommProtocol::makeCalibrationJson(*g_context.calibration, requestId);
        }

        if (command == "calibrate")
        {
            CalibrationData nextCalibration = *g_context.calibration;
            if (!CommDispatch::applyCalibrationFromJson(root, nextCalibration, errorMessage))
            {
                return CommProtocol::makeErrorJson(requestId, command, errorMessage);
            }

            *g_context.calibration = nextCalibration;
            Storage::saveCalibration(*g_context.calibration);
            Sensors::setCalibration(*g_context.calibration);
            return CommProtocol::makeCalibrationJson(*g_context.calibration, requestId);
        }

        if (command == "run_calibration")
        {
            return handleCalibrationRunCommand(root, requestId);
        }

        if (command == "clear_calibration")
        {
            *g_context.calibration = {};
            Storage::clearCalibration();
            Sensors::setCalibration(*g_context.calibration);
            return CommProtocol::makeCalibrationJson(*g_context.calibration, requestId);
        }

        if (command == "get_reference")
        {
            return CommProtocol::makeReferenceJson(*g_context.reference, requestId);
        }

        if (command == "set_reference")
        {
            ReferenceData nextReference = *g_context.reference;
            if (!CommDispatch::applyReferenceFromJson(root, nextReference, errorMessage))
            {
                return CommProtocol::makeErrorJson(requestId, command, errorMessage);
            }

            *g_context.reference = nextReference;
            Storage::saveReference(*g_context.reference);
            return CommProtocol::makeReferenceJson(*g_context.reference, requestId);
        }

        if (command == "set_zero")
        {
            return handleSetZeroCommand(root, requestId);
        }

        if (command == "clear_reference")
        {
            *g_context.reference = {};
            Storage::clearReference();
            return CommProtocol::makeReferenceJson(*g_context.reference, requestId);
        }

        if (command == "get_ota_status")
        {
            return CommProtocol::makeOtaStatusJson(*g_runtime);
        }

        if (command == "ota_begin")
        {
            uint32_t size = 0;
            JsonFieldResult result = CommJson::readUInt(root, "size", size);
            if (result != JsonFieldResult::Ok)
            {
                return CommProtocol::makeErrorJson(requestId, command, Messages::Protocol::campoDeveEssere("size", Messages::JsonTypes::kInteroSenzaSegno));
            }

            if (size == 0U)
            {
                return CommProtocol::makeErrorJson(requestId, command, Messages::Protocol::kOtaSizeNonValida);
            }

            if (g_runtime->updateInProgress)
            {
                return CommProtocol::makeErrorJson(requestId, command, Messages::Protocol::kOtaGiaInCorso);
            }

            if (!beginOtaSession(size))
            {
                return CommProtocol::makeErrorJson(requestId, command, g_runtime->lastError.length() > 0 ? g_runtime->lastError : String(Messages::Protocol::kOtaWriteFallita));
            }

            return CommProtocol::buildEnvelope("response", requestId, Messages::Protocol::kStatoAccettato, "\"cmd\":\"ota_begin\"");
        }

        if (command == "ota_end")
        {
            if (!g_runtime->updateInProgress)
            {
                return CommProtocol::makeErrorJson(requestId, command, Messages::Protocol::kOtaNonInCorso);
            }

            if (!completeOtaSession())
            {
                return CommProtocol::makeErrorJson(requestId, command, g_runtime->lastError);
            }

            return CommProtocol::makeOtaStatusJson(*g_runtime);
        }

        if (command == "ota_abort")
        {
            if (!g_runtime->updateInProgress)
            {
                return CommProtocol::makeErrorJson(requestId, command, Messages::Protocol::kOtaNonInCorso);
            }

            stopOtaSession(Messages::Protocol::kUploadInterrotto, true);
            return CommProtocol::makeOtaStatusJson(*g_runtime);
        }

        return CommProtocol::makeErrorJson(requestId, command, Messages::Protocol::kComandoSconosciuto);
    }

    class BleServerCallbacks final : public BLEServerCallbacks
    {
    public:
#if defined(CONFIG_NIMBLE_ENABLED)
        void onConnect(BLEServer* server, ble_gap_conn_desc* desc) override
        {
            if (g_runtime == nullptr || desc == nullptr)
            {
                return;
            }

            if (g_runtime->bleClientConnected && g_runtime->bleConnId != desc->conn_handle)
            {
                server->disconnect(desc->conn_handle);
                return;
            }

            g_runtime->bleClientConnected = true;
            g_runtime->bleClientAuthenticated = false;
            g_runtime->bleAdvertising = false;
            g_runtime->bleConnId = desc->conn_handle;
            g_runtime->bleMtu = server->getPeerMTU(desc->conn_handle);
            clearSubscriptions();
            Debug::info(Messages::Log::kBleClientConnected);
            (void)BLESecurity::startSecurity(desc->conn_handle);
        }

        void onDisconnect(BLEServer* server, ble_gap_conn_desc* desc) override
        {
            (void)server;
            if (g_runtime == nullptr || desc == nullptr)
            {
                return;
            }

            if (g_runtime->bleConnId == desc->conn_handle)
            {
                if (g_runtime->updateInProgress)
                {
                    stopOtaSession(Messages::Protocol::kUploadInterrotto, true);
                }

                g_runtime->bleClientConnected = false;
                g_runtime->bleClientAuthenticated = false;
                g_runtime->bleAdvertising = true;
                g_runtime->bleConnId = COMM_INVALID_BLE_CONN;
                g_runtime->bleMtu = 23;
                clearSubscriptions();
                Debug::info(Messages::Log::kBleClientDisconnected);
            }
        }

        void onMtuChanged(BLEServer* server, ble_gap_conn_desc* desc, uint16_t mtu) override
        {
            (void)server;
            if (g_runtime == nullptr || desc == nullptr)
            {
                return;
            }

            if (g_runtime->bleConnId == desc->conn_handle)
            {
                g_runtime->bleMtu = mtu;
            }
        }
#endif
    };

    class BleSecurityCallbacks final : public BLESecurityCallbacks
    {
    public:
        uint32_t onPassKeyRequest() override
        {
            return BLE_STATIC_PASSKEY;
        }

        bool onSecurityRequest() override
        {
            return true;
        }

        bool onConfirmPIN(uint32_t pin) override
        {
            return pin == BLE_STATIC_PASSKEY;
        }

        bool onAuthorizationRequest(uint16_t connHandle, uint16_t attrHandle, bool isRead) override
        {
            (void)connHandle;
            (void)attrHandle;
            (void)isRead;
            return true;
        }

#if defined(CONFIG_NIMBLE_ENABLED)
        void onAuthenticationComplete(ble_gap_conn_desc* desc) override
        {
            if (g_runtime == nullptr || desc == nullptr)
            {
                return;
            }

            const bool authenticated = desc->sec_state.encrypted && desc->sec_state.authenticated;
            if (g_runtime->bleConnId == desc->conn_handle)
            {
                g_runtime->bleClientAuthenticated = authenticated;
            }

            if (authenticated)
            {
                Debug::info(Messages::Log::kBleAuthenticationOk);
            }
            else
            {
                g_runtime->lastError = Messages::Log::kBleAuthenticationFailed;
                Debug::info(Messages::Log::kBleAuthenticationFailed);
                if (g_bleServer != nullptr)
                {
                    g_bleServer->disconnect(desc->conn_handle);
                }
            }
        }
#endif
    };

    class CommandCharacteristicCallbacks final : public BLECharacteristicCallbacks
    {
    public:
        void onWrite(BLECharacteristic* characteristic) override
        {
            if (characteristic == nullptr)
            {
                return;
            }

            const String value = characteristic->getValue();
            if (!enqueueCommand(reinterpret_cast<const uint8_t*>(value.c_str()), value.length()) && g_runtime != nullptr)
            {
                g_runtime->lastError = Messages::Protocol::kComandoBleOccupato;
            }
        }
    };

    class OtaDataCharacteristicCallbacks final : public BLECharacteristicCallbacks
    {
    public:
        void onWrite(BLECharacteristic* characteristic) override
        {
            if (characteristic == nullptr || g_runtime == nullptr)
            {
                return;
            }

            const size_t length = characteristic->getLength();
            if (length > kBleMaxOtaChunkBytes)
            {
                g_runtime->lastError = Messages::Protocol::kOtaChunkTroppoGrande;
                flagOtaQueueOverflow();
                return;
            }

            if (!enqueueOtaChunk(characteristic->getData(), length))
            {
                g_runtime->lastError = Messages::Protocol::kOtaBufferSaturo;
            }
        }
    };

    class NotifySubscriptionCallbacks final : public BLECharacteristicCallbacks
    {
    public:
        explicit NotifySubscriptionCallbacks(bool liveChannel) : m_liveChannel(liveChannel) {}

#if defined(CONFIG_NIMBLE_ENABLED)
        void onSubscribe(BLECharacteristic* characteristic, ble_gap_conn_desc* desc, uint16_t subValue) override
        {
            (void)characteristic;
            if (g_runtime == nullptr || desc == nullptr || g_runtime->bleConnId != desc->conn_handle)
            {
                return;
            }

            const bool subscribed = subValue != 0U;
            if (m_liveChannel)
            {
                g_runtime->bleLiveSubscribed = subscribed;
                g_liveStreamLogged = false;
                Debug::info(subscribed ? Messages::Log::kBleLiveSubscribed : Messages::Log::kBleLiveUnsubscribed);
            }
            else
            {
                g_runtime->bleResponseSubscribed = subscribed;
            }
        }
#endif

    private:
        bool m_liveChannel = false;
    };

    void startBleIfNeeded()
    {
        if (g_runtime == nullptr || g_runtime->bleReady)
        {
            return;
        }

        if (!BLEDevice::init(BLE_DEVICE_NAME))
        {
            g_runtime->lastError = Messages::Log::kBleInitFailed;
            Debug::info(Messages::Log::kBleInitFailed);
            return;
        }

        BLEDevice::setMTU(BLE_PREFERRED_MTU);

        BLESecurity security;
        security.setPassKey(true, BLE_STATIC_PASSKEY);
        security.setCapability(ESP_IO_CAP_OUT);
        security.setAuthenticationMode(true, true, true);
        BLEDevice::setSecurityCallbacks(new BleSecurityCallbacks());

        g_bleServer = BLEDevice::createServer();
        g_bleServer->setCallbacks(new BleServerCallbacks());
        g_bleServer->advertiseOnDisconnect(true);

        BLEService* service = g_bleServer->createService(BLE_SERVICE_UUID);
        g_commandRxCharacteristic = service->createCharacteristic(
            BLE_COMMAND_RX_UUID,
            BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_AUTHEN
        );
        g_responseTxCharacteristic = service->createCharacteristic(
            BLE_RESPONSE_TX_UUID,
            BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ_AUTHEN
        );
        g_liveTxCharacteristic = service->createCharacteristic(
            BLE_LIVE_TX_UUID,
            BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ_AUTHEN
        );
        g_otaDataRxCharacteristic = service->createCharacteristic(
            BLE_OTA_DATA_RX_UUID,
            BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR | BLECharacteristic::PROPERTY_WRITE_AUTHEN
        );

        g_commandRxCharacteristic->setCallbacks(new CommandCharacteristicCallbacks());
        g_responseTxCharacteristic->setCallbacks(new NotifySubscriptionCallbacks(false));
        g_liveTxCharacteristic->setCallbacks(new NotifySubscriptionCallbacks(true));
        g_otaDataRxCharacteristic->setCallbacks(new OtaDataCharacteristicCallbacks());

        service->start();

        BLEAdvertising* advertising = BLEDevice::getAdvertising();
        advertising->addServiceUUID(BLE_SERVICE_UUID);
        advertising->setScanResponse(true);
        advertising->setMinPreferred(0x06);
        advertising->setMaxPreferred(0x12);
        BLEDevice::startAdvertising();

        g_runtime->bleReady = true;
        g_runtime->bleAdvertising = true;
        g_runtime->bleMtu = BLEDevice::getMTU();
        Debug::info(Messages::Log::kBleAdvertisingStarted);
    }
}

namespace CommTransport
{
    void begin(CommTransportRuntime& runtime)
    {
        g_runtime = &runtime;

        runtime = {};
        runtime.bleConnId = COMM_INVALID_BLE_CONN;
        runtime.bleMtu = 23;

        StatusLed::begin();
        startBleIfNeeded();
    }

    void handleIncoming(const CommRequestContext& context, CommTransportRuntime& runtime)
    {
        g_runtime = &runtime;
        g_context = context;

        processQueuedOtaChunks();

        if (takeCommandOverflowFlag())
        {
            sendResponsePayload(CommProtocol::makeErrorJson("", "ble", Messages::Protocol::kComandoBleOccupato));
        }

        String command;
        if (popCommand(command))
        {
            sendResponsePayload(dispatchCommand(command));
        }

        if (runtime.rebootScheduled && millis() >= g_rebootAtMs)
        {
            ESP.restart();
        }

        StatusLed::update(*g_context.runtimeState, runtime);
    }

    void sendRuntimeState(const RuntimeState& runtimeState)
    {
        if (g_runtime == nullptr || g_runtime->updateInProgress || !liveChannelReady())
        {
            return;
        }

        if (!g_liveStreamLogged)
        {
            Debug::info(Messages::Log::kBleLiveStreamingStarted);
            g_liveStreamLogged = true;
        }

        // Il framing BLE resta invariato: cambia solo il contenuto del compact live,
        // che puo includere anche i campi GPS compact previsti dal protocollo.
        notifyFramed(g_liveTxCharacteristic, CommProtocol::makeRuntimeStateJson(runtimeState));
    }
}
