package com.example.ridescope.data.repository

import android.content.Context
import android.util.Log
import com.example.ridescope.BuildConfig
import com.example.ridescope.data.model.FirmwareInfo
import com.example.ridescope.data.model.FirmwareUpdateAvailability
import com.example.ridescope.data.model.FirmwareUpdateCheckState
import com.example.ridescope.data.model.FirmwareUpdateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.max

class FirmwareUpdateRepository(
    context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val telemetryRepository: TelemetryRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val appContext = context.applicationContext

    init {
        synchronizeInstalledApplicationState()
    }

    fun synchronizeInstalledApplicationState(): FirmwareUpdateCheckState {
        val currentAppMetadata = currentAppMetadata()
        val previousState = appSettingsRepository.currentFirmwareUpdateCheckState()
        val normalizedAppStatusMessage = PlayManagedAppUpdateMessage
        val normalizedStatusMessage = when {
            previousState.statusMessage == previousState.appStatusMessage -> normalizedAppStatusMessage
            previousState.statusMessage == "Installazione app avviata" -> normalizedAppStatusMessage
            previousState.statusMessage == "APK scaricato, completa l'installazione Android" -> normalizedAppStatusMessage
            else -> previousState.statusMessage
        }
        val normalizedState = previousState.copy(
            appCurrentBuild = currentAppMetadata.build,
            appCurrentTimestamp = currentAppMetadata.timestamp,
            appCurrentProtocol = currentAppMetadata.protocol,
            appRemoteBuild = "",
            appRemoteTimestamp = "",
            appRemoteProtocol = "",
            appAvailability = FirmwareUpdateAvailability.UpToDate,
            appStatusMessage = normalizedAppStatusMessage,
            statusMessage = normalizedStatusMessage,
            appUpdateInProgress = false,
            appUpdateProgressPercent = 0,
        )
        if (normalizedState == previousState) {
            return previousState
        }
        return publishState(normalizedState)
    }

    suspend fun checkForUpdate(force: Boolean): FirmwareUpdateCheckState? {
        val settings = appSettingsRepository.currentFirmwareUpdateSettings().normalized()
        val previousState = appSettingsRepository.currentFirmwareUpdateCheckState()
        if (!settings.isConfigured() && !force) {
            return null
        }
        if (!force && !shouldCheckNow(settings, previousState)) {
            return null
        }

        val currentAppMetadata = currentAppMetadata()
        val checkState = if (!settings.isConfigured()) {
            buildResult(
                currentInfo = runCatching { telemetryRepository.readInfo() }.getOrNull(),
                remoteManifest = null,
                availability = FirmwareUpdateAvailability.Error,
                statusMessage = "Configurazione HTTP incompleta",
                currentAppMetadata = currentAppMetadata,
                remoteAppManifest = null,
                appAvailability = FirmwareUpdateAvailability.UpToDate,
                appStatusMessage = PlayManagedAppUpdateMessage,
            )
        } else {
            val firmwareOutcome = checkFirmwareAvailability(settings)
            buildFirmwareCheckResult(
                firmwareOutcome = firmwareOutcome,
                currentAppMetadata = currentAppMetadata,
            )
        }

        return publishState(
            checkState.copy(
                updateInProgress = false,
                updateProgressPercent = 0,
                appUpdateInProgress = false,
                appUpdateProgressPercent = 0,
            ),
        )
    }

    suspend fun updateFirmware(): FirmwareUpdateCheckState {
        val settings = appSettingsRepository.currentFirmwareUpdateSettings().normalized()
        var workingState = appSettingsRepository.currentFirmwareUpdateCheckState().copy(
            updateInProgress = false,
            updateProgressPercent = 0,
            appUpdateInProgress = false,
            appUpdateProgressPercent = 0,
        )

        if (!settings.isConfigured()) {
            return publishState(
                workingState.copy(
                    availability = FirmwareUpdateAvailability.Error,
                    statusMessage = "Configurazione HTTP incompleta",
                ),
            )
        }

        return runCatching {
            val currentInfo = telemetryRepository.readInfo()
            if (!currentInfo.transport.otaBleEnabled) {
                error("OTA BLE non supportato dal firmware connesso")
            }

            val remoteManifest = fetchFirmwareManifest(settings)
            val comparisonState = buildFirmwareOnlyResult(currentInfo, remoteManifest)
                .withAppStateFrom(workingState)
            if (comparisonState.availability != FirmwareUpdateAvailability.UpdateAvailable) {
                workingState = comparisonState.copy(
                    updateInProgress = false,
                    updateProgressPercent = 0,
                    statusMessage = "Nessun nuovo firmware disponibile",
                )
                return@runCatching publishState(workingState)
            }

            workingState = publishProgress(
                baseState = comparisonState,
                progressPercent = 0,
                statusMessage = "Scaricamento firmware remoto...",
            )

            val firmwareBinary = fetchFirmwareBinary(settings) { bytesRead, totalBytes ->
                val progress = scaledProgress(
                    current = bytesRead,
                    total = totalBytes,
                    basePercent = 0,
                    spanPercent = 20,
                )
                workingState = publishProgress(
                    baseState = workingState,
                    progressPercent = progress,
                    statusMessage = formatTransferStatus("Scaricamento firmware remoto", bytesRead, totalBytes),
                )
            }

            workingState = publishProgress(
                baseState = workingState,
                progressPercent = 20,
                statusMessage = "Trasferimento OTA via BLE...",
            )

            val otaStatus = telemetryRepository.performOtaUpdate(firmwareBinary) { writtenBytes, totalBytes ->
                val progress = scaledProgress(
                    current = writtenBytes.toLong(),
                    total = totalBytes.toLong(),
                    basePercent = 20,
                    spanPercent = 75,
                )
                workingState = publishProgress(
                    baseState = workingState,
                    progressPercent = progress,
                    statusMessage = formatTransferStatus("Trasferimento OTA via BLE", writtenBytes.toLong(), totalBytes.toLong()),
                )
            }

            if (!otaStatus.otaSuccess) {
                error(otaStatus.lastError.ifBlank { "Aggiornamento OTA non riuscito" })
            }

            workingState = publishProgress(
                baseState = comparisonState.copy(
                    currentBuild = currentInfo.firmware.build,
                    currentTimestamp = currentInfo.firmware.timestamp,
                    currentProtocol = currentInfo.firmware.protocol,
                ),
                progressPercent = 97,
                statusMessage = "Firmware scritto, riavvio dispositivo in corso...",
            )

            val refreshedInfo = waitForUpdatedFirmware(remoteManifest)
        val finalState = if (refreshedInfo != null && manifestMatches(refreshedInfo, remoteManifest)) {
                buildResult(
                    currentInfo = refreshedInfo,
                    remoteManifest = remoteManifest,
                    availability = FirmwareUpdateAvailability.UpToDate,
                    statusMessage = "Firmware aggiornato correttamente",
                    currentAppMetadata = currentAppMetadata(),
                    remoteAppManifest = workingState.toRemoteAppManifestInternal(),
                    appAvailability = workingState.appAvailability,
                    appStatusMessage = workingState.appStatusMessage,
                ).copy(
                    updateInProgress = false,
                    updateProgressPercent = 100,
                )
            } else {
                buildResult(
                    currentInfo = refreshedInfo ?: currentInfo,
                    remoteManifest = remoteManifest,
                    availability = FirmwareUpdateAvailability.Unknown,
                    statusMessage = "OTA completato, ma la verifica post-riavvio non e riuscita",
                    currentAppMetadata = currentAppMetadata(),
                    remoteAppManifest = workingState.toRemoteAppManifestInternal(),
                    appAvailability = workingState.appAvailability,
                    appStatusMessage = workingState.appStatusMessage,
                ).copy(
                    updateInProgress = false,
                    updateProgressPercent = 100,
                )
            }

            publishState(finalState)
        }.getOrElse { error ->
            Log.e(Tag, "Aggiornamento OTA firmware fallito", error)
            publishState(
                workingState.copy(
                    availability = FirmwareUpdateAvailability.Error,
                    statusMessage = error.message ?: "Aggiornamento OTA fallito",
                    updateInProgress = false,
                ),
            )
        }
    }

    suspend fun updateApplication(): FirmwareUpdateCheckState {
        val currentAppMetadata = currentAppMetadata()
        val workingState = appSettingsRepository.currentFirmwareUpdateCheckState().copy(
            updateInProgress = false,
            updateProgressPercent = 0,
            appUpdateInProgress = false,
            appUpdateProgressPercent = 0,
            appCurrentBuild = currentAppMetadata.build,
            appCurrentTimestamp = currentAppMetadata.timestamp,
            appCurrentProtocol = currentAppMetadata.protocol,
            appRemoteBuild = "",
            appRemoteTimestamp = "",
            appRemoteProtocol = "",
            appAvailability = FirmwareUpdateAvailability.UpToDate,
            appStatusMessage = PlayManagedAppUpdateMessage,
            statusMessage = PlayManagedAppUpdateMessage,
        )
        return publishState(workingState)
    }

    private fun shouldCheckNow(
        settings: FirmwareUpdateSettings,
        previousState: FirmwareUpdateCheckState,
    ): Boolean {
        if (settings.checkIntervalMinutes <= 0) {
            return false
        }
        val lastCheckedAt = previousState.lastCheckedAtEpochMs ?: return true
        val intervalMs = settings.checkIntervalMinutes * 60L * 1000L
        return System.currentTimeMillis() - lastCheckedAt >= intervalMs
    }

    private fun buildFirmwareOnlyResult(
        currentInfo: FirmwareInfo,
        remoteManifest: ManifestMetadata,
    ): FirmwareUpdateCheckState {
        val buildComparison = compareVersion(remoteManifest.build, currentInfo.firmware.build)
        val timestampComparison = remoteManifest.timestamp.compareTo(currentInfo.firmware.timestamp)
        val protocolComparison = compareVersion(remoteManifest.protocol, currentInfo.firmware.protocol)

        val availability = when {
            buildComparison > 0 -> FirmwareUpdateAvailability.UpdateAvailable
            buildComparison < 0 -> FirmwareUpdateAvailability.UpToDate
            timestampComparison > 0 -> FirmwareUpdateAvailability.UpdateAvailable
            timestampComparison < 0 -> FirmwareUpdateAvailability.UpToDate
            protocolComparison > 0 -> FirmwareUpdateAvailability.UpdateAvailable
            else -> FirmwareUpdateAvailability.UpToDate
        }

        val statusMessage = when (availability) {
            FirmwareUpdateAvailability.UpdateAvailable ->
                "Nuova revisione firmware disponibile"
            FirmwareUpdateAvailability.UpToDate ->
                "Firmware allineato al manifest remoto"
            FirmwareUpdateAvailability.Unknown,
            FirmwareUpdateAvailability.Error,
            -> "Controllo firmware non determinato"
        }

        return buildResult(
            currentInfo = currentInfo,
            remoteManifest = remoteManifest,
            availability = availability,
            statusMessage = statusMessage,
            currentAppMetadata = currentAppMetadata(),
            remoteAppManifest = null,
            appAvailability = FirmwareUpdateAvailability.UpToDate,
            appStatusMessage = PlayManagedAppUpdateMessage,
        )
    }

    private fun buildResult(
        currentInfo: FirmwareInfo?,
        remoteManifest: ManifestMetadata?,
        availability: FirmwareUpdateAvailability,
        statusMessage: String,
        currentAppMetadata: ManifestMetadata?,
        remoteAppManifest: ManifestMetadata?,
        appAvailability: FirmwareUpdateAvailability,
        appStatusMessage: String,
    ): FirmwareUpdateCheckState {
        return FirmwareUpdateCheckState(
            lastCheckedAtEpochMs = System.currentTimeMillis(),
            currentBuild = currentInfo?.firmware?.build.orEmpty(),
            currentTimestamp = currentInfo?.firmware?.timestamp.orEmpty(),
            currentProtocol = currentInfo?.firmware?.protocol.orEmpty(),
            remoteBuild = remoteManifest?.build.orEmpty(),
            remoteTimestamp = remoteManifest?.timestamp.orEmpty(),
            remoteProtocol = remoteManifest?.protocol.orEmpty(),
            availability = availability,
            statusMessage = statusMessage,
            appCurrentBuild = currentAppMetadata?.build.orEmpty(),
            appCurrentTimestamp = currentAppMetadata?.timestamp.orEmpty(),
            appCurrentProtocol = currentAppMetadata?.protocol.orEmpty(),
            appRemoteBuild = remoteAppManifest?.build.orEmpty(),
            appRemoteTimestamp = remoteAppManifest?.timestamp.orEmpty(),
            appRemoteProtocol = remoteAppManifest?.protocol.orEmpty(),
            appAvailability = appAvailability,
            appStatusMessage = appStatusMessage,
        )
    }

    private suspend fun fetchFirmwareManifest(settings: FirmwareUpdateSettings): ManifestMetadata {
        val manifestContent = withContext(Dispatchers.IO) {
            downloadFileOverHttp(settings.manifestRemoteUrl())
        }
        return parseFirmwareManifest(String(manifestContent, StandardCharsets.UTF_8))
    }

    private suspend fun fetchFirmwareBinary(
        settings: FirmwareUpdateSettings,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): ByteArray {
        return withContext(Dispatchers.IO) {
            downloadFileOverHttp(
                remoteUrl = settings.firmwareBinaryRemoteUrl(),
                onProgress = onProgress,
            )
        }
    }

    private fun parseFirmwareManifest(rawJson: String): ManifestMetadata {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val firmwareRoot = root["firmware"]?.jsonObject
            ?: error("manifest.json remoto non contiene firmware{}")
        return ManifestMetadata(
            build = firmwareRoot.stringValue("build"),
            timestamp = firmwareRoot.stringValue("timestamp"),
            protocol = firmwareRoot.stringValue("protocol"),
        ).also { metadata ->
            if (metadata.build.isBlank() || metadata.timestamp.isBlank() || metadata.protocol.isBlank()) {
                error("manifest.json remoto incompleto")
            }
        }
    }

    private fun downloadFileOverHttp(
        remoteUrl: String,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): ByteArray {
        val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NetworkTimeoutMs
            readTimeout = NetworkTimeoutMs
            instanceFollowRedirects = true
        }
        return connection.useConnection { http ->
            val responseCode = http.responseCode
            if (responseCode !in 200..299) {
                val errorBody = http.errorStream?.use { stream ->
                    String(stream.readBytes(), StandardCharsets.UTF_8).trim()
                }.orEmpty()
                val responseMessage = http.responseMessage?.trim().orEmpty()
                val failureMessage = listOf(responseMessage, errorBody)
                    .firstOrNull { it.isNotBlank() }
                    ?: "HTTP $responseCode"
                error("Download HTTP fallito ($responseCode): $failureMessage")
            }
            val expectedSize = http.contentLengthLong.takeIf { it > 0L }
            BufferedInputStream(http.inputStream).use { input ->
                readBinaryContent(input, expectedSize, onProgress)
            }
        }
    }

    private fun readBinaryContent(
        input: BufferedInputStream,
        expectedSize: Long?,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val bufferedOutput = BufferedOutputStream(output)
        val buffer = ByteArray(DownloadReadBufferSize)
        var bytesReadTotal = 0L

        onProgress(0L, expectedSize)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            bufferedOutput.write(buffer, 0, read)
            bytesReadTotal += read
            onProgress(bytesReadTotal, expectedSize)
        }
        bufferedOutput.flush()
        return output.toByteArray()
    }

    private suspend fun waitForUpdatedFirmware(remoteManifest: ManifestMetadata): FirmwareInfo? {
        repeat(PostOtaVerificationAttempts) { attempt ->
            delay(if (attempt == 0) PostOtaInitialDelayMs else PostOtaRetryDelayMs)
            val info = runCatching { telemetryRepository.readInfo() }.getOrNull() ?: return@repeat
            if (manifestMatches(info, remoteManifest)) {
                return info
            }
        }
        return runCatching { telemetryRepository.readInfo() }.getOrNull()
    }

    private fun manifestMatches(
        info: FirmwareInfo,
        manifest: ManifestMetadata,
    ): Boolean {
        return info.firmware.build == manifest.build &&
            info.firmware.timestamp == manifest.timestamp &&
            info.firmware.protocol == manifest.protocol
    }

    private suspend fun checkFirmwareAvailability(settings: FirmwareUpdateSettings): CheckOutcome {
        if (!telemetryRepository.isBleConnected()) {
            return CheckOutcome(
                current = null,
                remote = null,
                availability = FirmwareUpdateAvailability.Unknown,
                statusMessage = "Dispositivo non connesso, controllo firmware saltato",
                currentInfo = null,
            )
        }
        val currentInfo = runCatching { telemetryRepository.readInfo() }.getOrElse { error ->
            Log.e(Tag, "Lettura get_info firmware fallita", error)
            return CheckOutcome(
                current = null,
                remote = null,
                availability = FirmwareUpdateAvailability.Error,
                statusMessage = error.message ?: "Lettura get_info firmware fallita",
                currentInfo = null,
            )
        }
        val remoteManifest = runCatching { fetchFirmwareManifest(settings) }.getOrElse { error ->
            Log.e(Tag, "Download manifest firmware fallito", error)
            return CheckOutcome(
                current = currentInfo.toManifestMetadata(),
                remote = null,
                availability = FirmwareUpdateAvailability.Error,
                statusMessage = error.message ?: "Download manifest firmware fallito",
                currentInfo = currentInfo,
            )
        }
        val assessment = assessUpdateAvailability(
            current = currentInfo.toManifestMetadata(),
            remote = remoteManifest,
            updateMessage = "Nuova revisione firmware disponibile",
            currentMessage = "Firmware allineato al manifest remoto",
        )
        return CheckOutcome(
            current = currentInfo.toManifestMetadata(),
            remote = remoteManifest,
            availability = assessment.availability,
            statusMessage = assessment.statusMessage,
            currentInfo = currentInfo,
        )
    }

    private fun buildFirmwareCheckResult(
        firmwareOutcome: CheckOutcome,
        currentAppMetadata: ManifestMetadata,
    ): FirmwareUpdateCheckState {
        val overallStatusMessage = when {
            firmwareOutcome.availability == FirmwareUpdateAvailability.UpdateAvailable ->
                "Nuova revisione firmware disponibile"
            firmwareOutcome.availability == FirmwareUpdateAvailability.Error ->
                firmwareOutcome.statusMessage
            firmwareOutcome.availability == FirmwareUpdateAvailability.UpToDate ->
                "Firmware allineato al manifest remoto"
            else -> "Controllo aggiornamenti completato"
        }

        return buildResult(
            currentInfo = firmwareOutcome.currentInfo,
            remoteManifest = firmwareOutcome.remote,
            availability = firmwareOutcome.availability,
            statusMessage = overallStatusMessage,
            currentAppMetadata = currentAppMetadata,
            remoteAppManifest = null,
            appAvailability = FirmwareUpdateAvailability.UpToDate,
            appStatusMessage = PlayManagedAppUpdateMessage,
        )
    }

    private fun assessUpdateAvailability(
        current: ManifestMetadata,
        remote: ManifestMetadata,
        updateMessage: String,
        currentMessage: String,
    ): AvailabilityAssessment {
        val buildComparison = compareVersion(remote.build, current.build)
        val timestampComparison = remote.timestamp.compareTo(current.timestamp)
        val protocolComparison = compareVersion(remote.protocol, current.protocol)
        val availability = when {
            buildComparison > 0 -> FirmwareUpdateAvailability.UpdateAvailable
            buildComparison < 0 -> FirmwareUpdateAvailability.UpToDate
            timestampComparison > 0 -> FirmwareUpdateAvailability.UpdateAvailable
            timestampComparison < 0 -> FirmwareUpdateAvailability.UpToDate
            protocolComparison > 0 -> FirmwareUpdateAvailability.UpdateAvailable
            else -> FirmwareUpdateAvailability.UpToDate
        }
        val statusMessage = when (availability) {
            FirmwareUpdateAvailability.UpdateAvailable -> updateMessage
            FirmwareUpdateAvailability.UpToDate -> currentMessage
            FirmwareUpdateAvailability.Unknown,
            FirmwareUpdateAvailability.Error,
            -> "Controllo non determinato"
        }
        return AvailabilityAssessment(availability, statusMessage)
    }

    private fun currentAppMetadata(): ManifestMetadata {
        return ManifestMetadata(
            build = BuildConfig.VERSION_CODE.toString(),
            timestamp = BuildConfig.RIDESCOPE_BUILD_TIMESTAMP,
            protocol = BuildConfig.RIDESCOPE_PROTOCOL,
        )
    }

    private fun FirmwareInfo.toManifestMetadata(): ManifestMetadata {
        return ManifestMetadata(
            build = firmware.build,
            timestamp = firmware.timestamp,
            protocol = firmware.protocol,
        )
    }

    private fun FirmwareUpdateCheckState.toRemoteAppManifestInternal(): ManifestMetadata? {
        if (appRemoteBuild.isBlank() && appRemoteTimestamp.isBlank() && appRemoteProtocol.isBlank()) {
            return null
        }
        return ManifestMetadata(
            build = appRemoteBuild,
            timestamp = appRemoteTimestamp,
            protocol = appRemoteProtocol,
        )
    }

    private fun publishProgress(
        baseState: FirmwareUpdateCheckState,
        progressPercent: Int,
        statusMessage: String,
    ): FirmwareUpdateCheckState {
        return publishState(
            baseState.copy(
                statusMessage = statusMessage,
                updateInProgress = true,
                updateProgressPercent = progressPercent.coerceIn(0, 100),
            ),
        )
    }

    private fun publishState(state: FirmwareUpdateCheckState): FirmwareUpdateCheckState {
        appSettingsRepository.updateFirmwareUpdateCheckState(
            state.copy(
                updateProgressPercent = state.updateProgressPercent.coerceIn(0, 100),
                appUpdateProgressPercent = state.appUpdateProgressPercent.coerceIn(0, 100),
            ),
        )
        return appSettingsRepository.currentFirmwareUpdateCheckState()
    }

    private fun scaledProgress(
        current: Long,
        total: Long?,
        basePercent: Int,
        spanPercent: Int,
    ): Int {
        if (total == null || total <= 0L) {
            return basePercent
        }
        val boundedCurrent = current.coerceIn(0L, total)
        val phasePercent = (boundedCurrent * spanPercent / total).toInt()
        return (basePercent + phasePercent).coerceIn(0, 100)
    }

    private fun formatTransferStatus(
        phase: String,
        bytesTransferred: Long,
        totalBytes: Long?,
    ): String {
        return if (totalBytes != null && totalBytes > 0L) {
            val percent = ((bytesTransferred * 100L) / totalBytes).coerceIn(0L, 100L)
            "$phase... $percent%"
        } else {
            "$phase..."
        }
    }

    private fun compareVersion(left: String, right: String): Int {
        if (left == right) {
            return 0
        }
        val leftParts = extractNumericParts(left)
        val rightParts = extractNumericParts(right)
        val maxSize = max(leftParts.size, rightParts.size)
        for (index in 0 until maxSize) {
            val leftPart = leftParts.getOrElse(index) { 0 }
            val rightPart = rightParts.getOrElse(index) { 0 }
            if (leftPart != rightPart) {
                return leftPart.compareTo(rightPart)
            }
        }
        return left.compareTo(right)
    }

    private fun extractNumericParts(value: String): List<Int> {
        return Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private data class ManifestMetadata(
        val build: String,
        val timestamp: String,
        val protocol: String,
    )

    private data class AvailabilityAssessment(
        val availability: FirmwareUpdateAvailability,
        val statusMessage: String,
    )

    private data class CheckOutcome(
        val current: ManifestMetadata?,
        val remote: ManifestMetadata?,
        val availability: FirmwareUpdateAvailability,
        val statusMessage: String,
        val currentInfo: FirmwareInfo?,
    )

    private companion object {
        const val Tag = "RideScopeUpdate"
        const val NetworkTimeoutMs = 15_000
        const val DownloadReadBufferSize = 8 * 1024
        const val PostOtaVerificationAttempts = 8
        const val PostOtaInitialDelayMs = 2_500L
        const val PostOtaRetryDelayMs = 2_000L
        const val PlayManagedAppUpdateMessage = "Aggiornamenti app gestiti da Google Play"
    }
}

private fun kotlinx.serialization.json.JsonObject?.stringValue(key: String): String {
    return this?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
}

private fun FirmwareUpdateCheckState.withAppStateFrom(other: FirmwareUpdateCheckState): FirmwareUpdateCheckState {
    return copy(
        appCurrentBuild = other.appCurrentBuild,
        appCurrentTimestamp = other.appCurrentTimestamp,
        appCurrentProtocol = other.appCurrentProtocol,
        appRemoteBuild = other.appRemoteBuild,
        appRemoteTimestamp = other.appRemoteTimestamp,
        appRemoteProtocol = other.appRemoteProtocol,
        appAvailability = other.appAvailability,
        appStatusMessage = other.appStatusMessage,
        appUpdateInProgress = other.appUpdateInProgress,
        appUpdateProgressPercent = other.appUpdateProgressPercent,
    )
}
