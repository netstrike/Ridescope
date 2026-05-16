package com.example.ridescope.data.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DefaultBleMtu = 23

data class BleConnectionState(
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val reconnecting: Boolean = false,
    val connected: Boolean = false,
    val authenticated: Boolean = false,
    val responseSubscribed: Boolean = false,
    val liveSubscribed: Boolean = false,
    val mtu: Int = DefaultBleMtu,
    val lastError: String? = null,
)

class FirmwareBleClient(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()

    private val _connectionState = MutableStateFlow(baseState())
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _liveFrames = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val liveFrames: SharedFlow<String> = _liveFrames.asSharedFlow()

    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val requestCounter = AtomicLong(0L)
    @Volatile
    private var pendingFallbackResponse: PendingFallbackResponse? = null

    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionWanted = false
    private var receiverRegistered = false

    private var gatt: BluetoothGatt? = null
    private var currentDevice: BluetoothDevice? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    private var liveCharacteristic: BluetoothGattCharacteristic? = null
    private var otaDataCharacteristic: BluetoothGattCharacteristic? = null
    private var readyDeferred: CompletableDeferred<Unit>? = null
    private var writeDeferred: CompletableDeferred<Unit>? = null
    private var bondDeferred: CompletableDeferred<Unit>? = null
    private var bondAddress: String? = null
    private var notificationStage: NotificationStage = NotificationStage.None

    private val responseBuffer = StringBuilder()
    private val liveBuffer = StringBuilder()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> handleBondStateChanged(intent)
                BluetoothAdapter.ACTION_STATE_CHANGED -> handleAdapterStateChanged(intent)
            }
        }
    }

    fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }.toTypedArray()

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun isBluetoothEnabled(): Boolean = runCatching {
        bluetoothManager?.adapter?.isEnabled == true
    }.getOrDefault(false)

    fun start() {
        connectionWanted = true
        ensureReceiverRegistered()
        reconnectJob?.cancel()
        reconnectJob = null
        refreshStaticState()

        if (!hasRequiredPermissions()) {
            updateState {
                baseState(lastError = "Permessi BLE mancanti").copy(
                    reconnecting = false,
                    authenticated = currentDevice?.bondState == BluetoothDevice.BOND_BONDED,
                )
            }
            return
        }

        if (!isBluetoothEnabled()) {
            updateState {
                baseState(lastError = "Bluetooth disattivato").copy(
                    reconnecting = false,
                    authenticated = currentDevice?.bondState == BluetoothDevice.BOND_BONDED,
                )
            }
            return
        }

        val snapshot = _connectionState.value
        if (snapshot.connected || snapshot.scanning || snapshot.connecting || connectJob?.isActive == true) {
            return
        }

        connectJob = scope.launch {
            runConnectionAttempt()
        }
    }

    fun close() {
        connectionWanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        failPendingOperations("Trasporto BLE chiuso")
        closeGatt()
        unregisterReceiver()
        scope.cancel()
    }

    suspend fun sendCommand(
        command: String,
        fallbackMode: ResponseFallbackMode = ResponseFallbackMode.None,
        fields: JsonObjectBuilder.() -> Unit = {},
    ): String {
        start()
        awaitResponseChannelReady()
        return commandMutex.withLock {
            sendCommandLocked(command, fallbackMode, fields)
        }
    }

    suspend fun transferOtaFirmware(
        firmwareBinary: ByteArray,
        onProgress: (writtenBytes: Int, totalBytes: Int) -> Unit = { _, _ -> },
    ): String {
        require(firmwareBinary.isNotEmpty()) { "Firmware OTA vuoto" }

        start()
        awaitResponseChannelReady()

        return commandMutex.withLock {
            awaitResponseChannelReady()
            ensureOtaChannelReady()

            val totalBytes = firmwareBinary.size
            onProgress(0, totalBytes)

            var otaStarted = false
            try {
                val beginResponse = sendCommandLocked("ota_begin") {
                    put("size", JsonPrimitive(totalBytes))
                }
                ensureAcceptedOrSuccessful(beginResponse, "ota_begin")
                otaStarted = true

                val chunkSize = currentOtaChunkSize()
                var otaWriteMode = currentOtaWriteMode()
                Log.i(
                    Tag,
                    "OTA BLE: bytes=$totalBytes chunk=$chunkSize write=${otaWriteModeName(otaWriteMode.writeType)} drain=$OtaDrainChunkBatch/$OtaDrainDelayMs ms",
                )
                var offset = 0
                var chunkCounter = 0
                while (offset < totalBytes) {
                    val nextOffset = minOf(offset + chunkSize, totalBytes)
                    val chunkBytes = firmwareBinary.copyOfRange(offset, nextOffset)
                    while (true) {
                        try {
                            writeOtaChunk(
                                bytes = chunkBytes,
                                writeType = otaWriteMode.writeType,
                                awaitWriteCompletion = otaWriteMode.awaitWriteCompletion,
                                writeStartRetryDelayMs = otaWriteMode.writeStartRetryDelayMs,
                                writeStartMaxAttempts = otaWriteMode.writeStartMaxAttempts,
                            )
                            break
                        } catch (error: BleWriteStartFailedException) {
                            val fallbackMode = otaWriteMode.fallbackToWriteWithResponse()
                            if (fallbackMode == null) {
                                throw error
                            }
                            Log.w(
                                Tag,
                                "OTA BLE: backlog GATT persistente su ${otaWriteModeName(otaWriteMode.writeType)}, fallback a ${otaWriteModeName(fallbackMode.writeType)}",
                                error,
                            )
                            otaWriteMode = fallbackMode
                        }
                    }
                    offset = nextOffset
                    onProgress(offset, totalBytes)
                    chunkCounter += 1
                    if (otaWriteMode.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                        delay(OtaNoResponseInterChunkDelayMs)
                    }
                    if (chunkCounter % OtaDrainChunkBatch == 0) {
                        delay(OtaDrainDelayMs)
                    }
                }

                val endResponse = sendCommandLocked(
                    command = "ota_end",
                    fallbackMode = ResponseFallbackMode.OtaStatus,
                )
                ensureAcceptedOrSuccessful(endResponse, "ota_end")
                return endResponse
            } catch (error: Throwable) {
                if (otaStarted) {
                    runCatching {
                        sendCommandLocked(
                            command = "ota_abort",
                            fallbackMode = ResponseFallbackMode.OtaStatus,
                        )
                    }
                }
                throw error
            }
        }
    }

    private suspend fun runConnectionAttempt() {
        try {
            updateState {
                baseState().copy(
                    reconnecting = true,
                    authenticated = currentDevice?.bondState == BluetoothDevice.BOND_BONDED,
                )
            }

            val device = resolveDeviceForConnection()
            currentDevice = device

            updateState {
                _connectionState.value.copy(
                    scanning = false,
                    connecting = true,
                    reconnecting = true,
                    authenticated = device.bondState == BluetoothDevice.BOND_BONDED,
                    lastError = null,
                )
            }

            ensureBonded(device)
            connectGattAndAwaitReady(device)

            updateState {
                _connectionState.value.copy(
                    scanning = false,
                    connecting = false,
                    reconnecting = false,
                    connected = true,
                    authenticated = device.bondState == BluetoothDevice.BOND_BONDED,
                    lastError = null,
                )
            }
        } catch (error: Throwable) {
            if (!connectionWanted) {
                return
            }
            Log.w(Tag, "Connessione BLE fallita: ${error.message}", error)
            handleTransportDrop(
                message = error.message ?: "Errore BLE sconosciuto",
                scheduleReconnect = hasRequiredPermissions() && isBluetoothEnabled(),
            )
        } finally {
            connectJob = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun resolveDeviceForConnection(): BluetoothDevice {
        currentDevice
            ?.takeIf { isDeviceBonded(it) }
            ?.let { bondedDevice ->
                Log.i(Tag, "Riuso device BLE bondato ${bondedDevice.address}")
                return bondedDevice
            }

        findBondedRideScopeDevice()?.let { bondedDevice ->
            Log.i(Tag, "Uso device BLE bondato da cache sistema ${bondedDevice.address}")
            currentDevice = bondedDevice
            return bondedDevice
        }

        updateState {
            _connectionState.value.copy(
                scanning = true,
                reconnecting = true,
                authenticated = currentDevice?.bondState == BluetoothDevice.BOND_BONDED,
                lastError = null,
            )
        }

        return scanForDevice()
    }

    @SuppressLint("MissingPermission")
    private fun findBondedRideScopeDevice(): BluetoothDevice? {
        val bondedDevices = bluetoothManager?.adapter?.bondedDevices.orEmpty()
        return bondedDevices.firstOrNull { it.name == DeviceName }
    }

    @SuppressLint("MissingPermission")
    private fun findBondedDeviceByAddress(address: String): BluetoothDevice? {
        val bondedDevices = bluetoothManager?.adapter?.bondedDevices.orEmpty()
        return bondedDevices.firstOrNull { it.address == address }
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceBonded(device: BluetoothDevice): Boolean {
        return device.bondState == BluetoothDevice.BOND_BONDED ||
            findBondedDeviceByAddress(device.address) != null
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForDevice(): BluetoothDevice = withTimeout(ScanTimeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
                ?: run {
                    continuation.resumeWithException(IllegalStateException("Scanner BLE non disponibile"))
                    return@suspendCancellableCoroutine
                }

            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(ServiceUuid))
                    .build(),
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val advertisedName = result.scanRecord?.deviceName ?: device.name
                    if (advertisedName == DeviceName || result.scanRecord?.serviceUuids?.contains(ParcelUuid(ServiceUuid)) == true) {
                        runCatching { scanner.stopScan(this) }
                        if (continuation.isActive) {
                            continuation.resume(device)
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Scan BLE fallita ($errorCode)"),
                        )
                    }
                }
            }

            scanner.startScan(filters, settings, callback)
            continuation.invokeOnCancellation {
                runCatching { scanner.stopScan(callback) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureBonded(device: BluetoothDevice) {
        if (isDeviceBonded(device)) {
            updateState { _connectionState.value.copy(authenticated = true) }
            return
        }

        val deferred = CompletableDeferred<Unit>()
        bondDeferred = deferred
        bondAddress = device.address

        val started = when (device.bondState) {
            BluetoothDevice.BOND_BONDING -> true
            else -> device.createBond()
        }
        if (!started) {
            bondDeferred = null
            bondAddress = null
            throw IllegalStateException("Pairing BLE non avviato")
        }

        updateState { _connectionState.value.copy(authenticated = false) }
        val bonded = try {
            withTimeoutOrNull(BondTimeoutMs) {
                while (true) {
                    if (isDeviceBonded(device)) {
                        return@withTimeoutOrNull true
                    }

                    if (deferred.isCompleted) {
                        deferred.await()
                        return@withTimeoutOrNull true
                    }

                    delay(BondPollIntervalMs)
                }
            } == true
        } finally {
            bondDeferred = null
            bondAddress = null
        }

        if (!bonded && !isDeviceBonded(device)) {
            throw IllegalStateException("Bond BLE non completato entro ${BondTimeoutMs} ms")
        }

        currentDevice = findBondedDeviceByAddress(device.address) ?: device
        updateState { _connectionState.value.copy(authenticated = true) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectGattAndAwaitReady(device: BluetoothDevice) {
        val deferred = CompletableDeferred<Unit>()
        readyDeferred = deferred

        val newGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, RideScopeGattCallback(), BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, RideScopeGattCallback())
        } ?: throw IllegalStateException("connectGatt ha restituito null")

        gatt = newGatt
        withTimeout(ConnectionSetupTimeoutMs) { deferred.await() }
    }

    private fun completeCommandChannelReady() {
        updateState {
            _connectionState.value.copy(
                connecting = false,
                reconnecting = false,
                connected = true,
                responseSubscribed = true,
                lastError = null,
            )
        }
        readyDeferred?.complete(Unit)
        readyDeferred = null
    }

    private fun handleOptionalLiveSubscriptionUnavailable(message: String) {
        Log.w(Tag, message)
        updateState {
            _connectionState.value.copy(
                connecting = false,
                reconnecting = false,
                connected = true,
                liveSubscribed = false,
            )
        }
        notificationStage = NotificationStage.Ready
    }

    private suspend fun awaitResponseChannelReady() {
        start()
        withTimeout(ConnectionSetupTimeoutMs) {
            connectionState.first { state ->
                state.permissionsGranted &&
                    state.bluetoothEnabled &&
                    state.connected &&
                    state.responseSubscribed
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeCommand(payload: String) {
        writeCharacteristic(
            characteristic = commandCharacteristic ?: throw IllegalStateException("Characteristic command_rx assente"),
            bytes = payload.toByteArray(StandardCharsets.UTF_8),
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeOtaChunk(
        bytes: ByteArray,
        writeType: Int,
        awaitWriteCompletion: Boolean,
        writeStartRetryDelayMs: Long,
        writeStartMaxAttempts: Int,
    ) {
        writeCharacteristic(
            characteristic = otaDataCharacteristic ?: throw IllegalStateException("Characteristic ota_data_rx assente"),
            bytes = bytes,
            writeType = writeType,
            awaitWriteCompletion = awaitWriteCompletion,
            writeStartRetryDelayMs = writeStartRetryDelayMs,
            writeStartMaxAttempts = writeStartMaxAttempts,
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
        writeType: Int,
        awaitWriteCompletion: Boolean = true,
        writeStartRetryDelayMs: Long = WriteStartRetryDelayMs,
        writeStartMaxAttempts: Int = WriteStartMaxAttempts,
    ) {
        val gatt = gatt ?: throw IllegalStateException("Gatt non connesso")
        repeat(writeStartMaxAttempts) { attemptIndex ->
            val deferred = if (awaitWriteCompletion) {
                CompletableDeferred<Unit>().also { writeDeferred = it }
            } else {
                null
            }

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    writeType,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }

            if (started) {
                if (deferred != null) {
                    try {
                        withTimeout(WriteTimeoutMs) { deferred.await() }
                    } finally {
                        if (writeDeferred === deferred) {
                            writeDeferred = null
                        }
                    }
                }
                return
            }

            if (deferred != null && writeDeferred === deferred) {
                writeDeferred = null
            }
            if (attemptIndex == writeStartMaxAttempts - 1) {
                throw BleWriteStartFailedException("Write BLE non avviata")
            }
            delay(writeStartRetryDelayMs)
        }
    }

    private suspend fun sendCommandLocked(
        command: String,
        fallbackMode: ResponseFallbackMode = ResponseFallbackMode.None,
        fields: JsonObjectBuilder.() -> Unit = {},
    ): String {
        val requestId = nextRequestId()
        val responseDeferred = CompletableDeferred<String>()
        val payload = buildJsonObject {
            put("id", JsonPrimitive(requestId))
            put("cmd", JsonPrimitive(command))
            fields()
        }.toString()

        pendingResponses[requestId] = responseDeferred
        if (fallbackMode != ResponseFallbackMode.None) {
            pendingFallbackResponse = PendingFallbackResponse(
                mode = fallbackMode,
                deferred = responseDeferred,
            )
        }

        return try {
            awaitResponseChannelReady()
            writeCommand(payload)
            withTimeout(ResponseTimeoutMs) { responseDeferred.await() }
        } finally {
            pendingResponses.remove(requestId)
            clearPendingFallback(responseDeferred)
        }
    }

    private fun ensureOtaChannelReady() {
        if (otaDataCharacteristic == null) {
            throw IllegalStateException("Characteristic ota_data_rx assente")
        }
    }

    private fun currentOtaChunkSize(): Int {
        val mtuPayload = (_connectionState.value.mtu - 3).coerceAtLeast(DefaultOtaChunkSize)
        return minOf(MaxOtaChunkSize, mtuPayload)
    }

    private fun currentOtaWriteMode(): OtaWriteMode {
        val characteristic = otaDataCharacteristic ?: return OtaWriteMode(
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            awaitWriteCompletion = true,
            writeStartRetryDelayMs = WriteStartRetryDelayMs,
            writeStartMaxAttempts = WriteStartMaxAttempts,
        )
        val supportsNoResponse =
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        return if (supportsNoResponse) {
            OtaWriteMode(
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                awaitWriteCompletion = false,
                writeStartRetryDelayMs = OtaNoResponseWriteStartRetryDelayMs,
                writeStartMaxAttempts = OtaNoResponseWriteStartMaxAttempts,
            )
        } else {
            OtaWriteMode(
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                awaitWriteCompletion = true,
                writeStartRetryDelayMs = WriteStartRetryDelayMs,
                writeStartMaxAttempts = WriteStartMaxAttempts,
            )
        }
    }

    private fun otaWriteModeName(writeType: Int): String = when (writeType) {
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE -> "without_response"
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT -> "with_response"
        BluetoothGattCharacteristic.WRITE_TYPE_SIGNED -> "signed"
        else -> "unknown($writeType)"
    }

    private fun ensureAcceptedOrSuccessful(
        rawJson: String,
        command: String,
    ) {
        val root = runCatching {
            json.parseToJsonElement(rawJson).jsonObject
        }.getOrNull() ?: return

        val status = root["status"]?.jsonPrimitive?.contentOrNull ?: return
        when (status) {
            "ok", "accettato" -> return
            "errore" -> {
                val errorMessage = root["error"]?.jsonPrimitive?.contentOrNull
                    ?: "Errore firmware durante $command"
                throw IllegalStateException(errorMessage)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            return false
        }

        val descriptor = characteristic.getDescriptor(ClientCharacteristicConfigUuid) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleResponseLine(line: String) {
        val root = runCatching {
            json.parseToJsonElement(line).jsonObject
        }.getOrNull()

        val requestId = root
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull

        if (requestId != null) {
            if (pendingResponses.remove(requestId)?.complete(line) == true) {
                return
            }
            Log.w(Tag, "Risposta BLE con id sconosciuto $requestId: $line")
            return
        }

        if (root != null && tryCompleteFallbackResponse(root, line)) {
            return
        }

        Log.w(Tag, "Risposta BLE senza id non instradata: $line")
    }

    private fun tryCompleteFallbackResponse(
        root: JsonObject,
        line: String,
    ): Boolean {
        val pending = pendingFallbackResponse ?: return false
        if (!pending.mode.matches(root)) {
            return false
        }

        clearPendingFallback(pending.deferred)
        pending.deferred.complete(line)
        return true
    }

    private fun clearPendingFallback(deferred: CompletableDeferred<String>) {
        if (pendingFallbackResponse?.deferred == deferred) {
            pendingFallbackResponse = null
        }
    }

    private fun appendChunk(
        buffer: StringBuilder,
        chunk: String,
        onLine: (String) -> Unit,
    ) {
        if (buffer.length + chunk.length > MaxBufferBytes) {
            Log.w(Tag, "Buffer BLE overflow (${buffer.length} byte) — reset")
            buffer.setLength(0)
        }
        buffer.append(chunk)
        while (true) {
            val newlineIndex = buffer.indexOf("\n")
            if (newlineIndex < 0) {
                return
            }
            val line = buffer.substring(0, newlineIndex).trim()
            buffer.delete(0, newlineIndex + 1)
            if (line.isNotEmpty()) {
                onLine(line)
            }
        }
    }

    private fun handleBondStateChanged(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        if (device.address != bondAddress) {
            return
        }

        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
            BluetoothDevice.BOND_BONDING -> updateState { _connectionState.value.copy(authenticated = false) }
            BluetoothDevice.BOND_BONDED -> {
                updateState { _connectionState.value.copy(authenticated = true) }
                bondDeferred?.complete(Unit)
                bondDeferred = null
                bondAddress = null
            }
            BluetoothDevice.BOND_NONE -> {
                val previousState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                if (previousState == BluetoothDevice.BOND_BONDING) {
                    bondDeferred?.completeExceptionally(IllegalStateException("Pairing BLE rifiutato"))
                    bondDeferred = null
                    bondAddress = null
                }
            }
        }
    }

    private fun handleAdapterStateChanged(intent: Intent) {
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
            BluetoothAdapter.STATE_OFF -> handleTransportDrop(
                message = "Bluetooth disattivato",
                scheduleReconnect = false,
            )
            BluetoothAdapter.STATE_ON -> {
                refreshStaticState()
                if (connectionWanted) {
                    start()
                }
            }
        }
    }

    private fun handleTransportDrop(
        message: String,
        scheduleReconnect: Boolean,
    ) {
        failPendingOperations(message)
        closeGatt()

        updateState {
            baseState(lastError = message).copy(
                reconnecting = scheduleReconnect && connectionWanted,
                authenticated = currentDevice?.bondState == BluetoothDevice.BOND_BONDED,
            )
        }

        if (scheduleReconnect && connectionWanted) {
            scheduleReconnect()
        }
    }

    private fun failPendingOperations(message: String) {
        val error = IllegalStateException(message)
        readyDeferred?.completeExceptionally(error)
        readyDeferred = null
        writeDeferred?.completeExceptionally(error)
        writeDeferred = null
        bondDeferred?.completeExceptionally(error)
        bondDeferred = null
        bondAddress = null
        pendingFallbackResponse?.deferred?.completeExceptionally(error)
        pendingFallbackResponse = null
        pendingResponses.values.forEach { it.completeExceptionally(error) }
        pendingResponses.clear()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(graceful: Boolean = true) {
        gatt?.let { currentGatt ->
            if (graceful) {
                runCatching { currentGatt.disconnect() }
            }
            runCatching { currentGatt.close() }
        }
        gatt = null
        commandCharacteristic = null
        responseCharacteristic = null
        liveCharacteristic = null
        otaDataCharacteristic = null
        notificationStage = NotificationStage.None
        responseBuffer.setLength(0)
        liveBuffer.setLength(0)
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) {
            return
        }
        reconnectJob = scope.launch {
            delay(ReconnectDelayMs)
            reconnectJob = null
            start()
        }
    }

    private fun ensureReceiverRegistered() {
        if (receiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(bluetoothReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) {
            return
        }
        runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
        receiverRegistered = false
    }

    private fun refreshStaticState() {
        updateState {
            _connectionState.value.copy(
                permissionsGranted = hasRequiredPermissions(),
                bluetoothEnabled = isBluetoothEnabled(),
            )
        }
    }

    private fun updateState(transform: (BleConnectionState) -> BleConnectionState) {
        _connectionState.value = transform(_connectionState.value)
    }

    private fun baseState(lastError: String? = null): BleConnectionState {
        return BleConnectionState(
            permissionsGranted = hasRequiredPermissions(),
            bluetoothEnabled = isBluetoothEnabled(),
            mtu = DefaultBleMtu,
            lastError = lastError,
        )
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun nextRequestId(): String = "app-${System.currentTimeMillis()}-${requestCounter.incrementAndGet()}"

    private inner class RideScopeGattCallback : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    handleTransportDrop(
                        message = "Errore GATT ($status)",
                        scheduleReconnect = hasRequiredPermissions() && isBluetoothEnabled(),
                    )
                }

                newState == BluetoothGatt.STATE_CONNECTED -> {
                    updateState {
                        _connectionState.value.copy(
                            scanning = false,
                            connecting = true,
                            reconnecting = true,
                            connected = true,
                            authenticated = currentDevice?.bondState == BluetoothDevice.BOND_BONDED,
                            lastError = null,
                        )
                    }
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    if (!gatt.discoverServices()) {
                        handleTransportDrop(
                            message = "Service discovery BLE non avviata",
                            scheduleReconnect = true,
                        )
                    }
                }

                newState == BluetoothGatt.STATE_DISCONNECTED -> {
                    handleTransportDrop(
                        message = "BLE disconnesso",
                        scheduleReconnect = hasRequiredPermissions() && isBluetoothEnabled(),
                    )
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleTransportDrop("Service discovery BLE fallita ($status)", scheduleReconnect = true)
                return
            }

            val service = gatt.getService(ServiceUuid)
            commandCharacteristic = service?.getCharacteristic(CommandRxUuid)
            responseCharacteristic = service?.getCharacteristic(ResponseTxUuid)
            liveCharacteristic = service?.getCharacteristic(LiveTxUuid)
            otaDataCharacteristic = service?.getCharacteristic(OtaDataRxUuid)

            if (commandCharacteristic == null || responseCharacteristic == null) {
                handleTransportDrop("Characteristic BLE mancanti", scheduleReconnect = true)
                return
            }
            if (liveCharacteristic == null) {
                Log.w(Tag, "Characteristic live_tx assente: proseguo in modalita solo comandi")
            }
            if (otaDataCharacteristic == null) {
                Log.w(Tag, "Characteristic ota_data_rx assente: OTA BLE non disponibile")
            }

            val mtuRequested = runCatching { gatt.requestMtu(PreferredMtu) }.getOrDefault(false)
            if (!mtuRequested) {
                updateState { _connectionState.value.copy(mtu = DefaultBleMtu) }
                notificationStage = NotificationStage.EnableResponse
                if (!enableNotifications(gatt, responseCharacteristic!!)) {
                    handleTransportDrop("Subscribe response_tx fallita", scheduleReconnect = true)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateState { _connectionState.value.copy(mtu = DefaultBleMtu) }
                handleTransportDrop("Negoziazione MTU fallita ($status)", scheduleReconnect = true)
                return
            }

            updateState { _connectionState.value.copy(mtu = mtu) }
            if (notificationStage != NotificationStage.None) {
                return
            }
            notificationStage = NotificationStage.EnableResponse
            if (!enableNotifications(gatt, responseCharacteristic ?: return)) {
                handleTransportDrop("Subscribe response_tx fallita", scheduleReconnect = true)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.characteristic.uuid == LiveTxUuid) {
                    handleOptionalLiveSubscriptionUnavailable("Subscribe live_tx fallita ($status)")
                } else {
                    handleTransportDrop("Subscribe BLE fallita ($status)", scheduleReconnect = true)
                }
                return
            }

            when (descriptor.characteristic.uuid) {
                ResponseTxUuid -> {
                    completeCommandChannelReady()
                    val live = liveCharacteristic
                    if (live == null) {
                        notificationStage = NotificationStage.Ready
                        return
                    }

                    notificationStage = NotificationStage.EnableLive
                    if (!enableNotifications(gatt, live)) {
                        handleOptionalLiveSubscriptionUnavailable("Subscribe live_tx fallita")
                    }
                }

                LiveTxUuid -> {
                    updateState {
                        _connectionState.value.copy(
                            connecting = false,
                            reconnecting = false,
                            connected = true,
                            liveSubscribed = true,
                            lastError = null,
                        )
                    }
                    notificationStage = NotificationStage.Ready
                    readyDeferred?.complete(Unit)
                    readyDeferred = null
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val deferred = writeDeferred ?: return
            writeDeferred = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                deferred.complete(Unit)
            } else {
                deferred.completeExceptionally(IllegalStateException("Write BLE fallita ($status)"))
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleCharacteristicChanged(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }
    }

    private fun handleCharacteristicChanged(
        characteristicUuid: UUID,
        value: ByteArray,
    ) {
        val chunk = value.toString(StandardCharsets.UTF_8)
        when (characteristicUuid) {
            ResponseTxUuid -> appendChunk(responseBuffer, chunk, ::handleResponseLine)
            LiveTxUuid -> appendChunk(liveBuffer, chunk) { line ->
                _liveFrames.tryEmit(line)
            }
        }
    }

    private enum class NotificationStage {
        None,
        EnableResponse,
        EnableLive,
        Ready,
    }

    private data class PendingFallbackResponse(
        val mode: ResponseFallbackMode,
        val deferred: CompletableDeferred<String>,
    )

    private data class OtaWriteMode(
        val writeType: Int,
        val awaitWriteCompletion: Boolean,
        val writeStartRetryDelayMs: Long,
        val writeStartMaxAttempts: Int,
    ) {
        fun fallbackToWriteWithResponse(): OtaWriteMode? {
            if (writeType != BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                return null
            }
            return OtaWriteMode(
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                awaitWriteCompletion = true,
                writeStartRetryDelayMs = WriteStartRetryDelayMs,
                writeStartMaxAttempts = WriteStartMaxAttempts,
            )
        }
    }

    enum class ResponseFallbackMode {
        None,
        LiveTelemetry,
        OtaStatus,
        ;

        fun matches(root: JsonObject): Boolean = when (this) {
            None -> false
            LiveTelemetry -> isLiveTelemetryPayload(root)
            OtaStatus -> root["type"]?.jsonPrimitive?.contentOrNull == "ota_status"
        }
    }

    companion object {
        private const val Tag = "RideScopeBle"
        private const val DeviceName = "RideScope"
        private const val PreferredMtu = 247
        private const val MaxOtaChunkSize = 244
        private const val DefaultOtaChunkSize = 20
        private const val OtaDrainChunkBatch = 8
        private const val OtaDrainDelayMs = 12L
        private const val ScanTimeoutMs = 15_000L
        private const val BondTimeoutMs = 60_000L
        private const val BondPollIntervalMs = 250L
        private const val ConnectionSetupTimeoutMs = 25_000L
        private const val WriteStartMaxAttempts = 4
        private const val WriteStartRetryDelayMs = 120L
        private const val OtaNoResponseWriteStartMaxAttempts = 12
        private const val OtaNoResponseWriteStartRetryDelayMs = 16L
        private const val OtaNoResponseInterChunkDelayMs = 12L
        private const val WriteTimeoutMs = 5_000L
        private const val ResponseTimeoutMs = 5_000L
        private const val ReconnectDelayMs = 1_500L
        private const val MaxBufferBytes = 65_536

        private val ServiceUuid: UUID = UUID.fromString("58f2a0f5-3d5e-4697-b3c9-67dbe7f5a501")
        private val CommandRxUuid: UUID = UUID.fromString("58f2a0f5-3d5e-4697-b3c9-67dbe7f5a502")
        private val ResponseTxUuid: UUID = UUID.fromString("58f2a0f5-3d5e-4697-b3c9-67dbe7f5a503")
        private val LiveTxUuid: UUID = UUID.fromString("58f2a0f5-3d5e-4697-b3c9-67dbe7f5a504")
        private val OtaDataRxUuid: UUID = UUID.fromString("58f2a0f5-3d5e-4697-b3c9-67dbe7f5a505")
        private val ClientCharacteristicConfigUuid: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

private class BleWriteStartFailedException(
    message: String,
) : IllegalStateException(message)

private fun isLiveTelemetryPayload(root: JsonObject): Boolean {
    return root["ts"] != null &&
        root["r"] != null &&
        root["p"] != null &&
        root["rr"] != null &&
        root["rp"] != null &&
        root["ay"] != null &&
        root["ir"] != null
}
