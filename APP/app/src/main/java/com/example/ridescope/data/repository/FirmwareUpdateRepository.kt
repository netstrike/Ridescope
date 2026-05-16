package com.example.ridescope.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
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
import java.io.File
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
        val remoteAppManifest = previousState.toRemoteAppManifestInternal()
        val appAssessment = remoteAppManifest?.let {
            assessUpdateAvailability(
                current = currentAppMetadata,
                remote = it,
                updateMessage = "Nuova build app disponibile",
                currentMessage = "App allineata al manifest remoto",
            )
        }
        val normalizedAppStatusMessage = appAssessment?.statusMessage ?: previousState.appStatusMessage
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
            appAvailability = appAssessment?.availability ?: previousState.appAvailability,
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
                appAvailability = FirmwareUpdateAvailability.Error,
                appStatusMessage = "Configurazione HTTP incompleta",
            )
        } else {
            val firmwareOutcome = checkFirmwareAvailability(settings)
            val appOutcome = checkAppAvailability(settings, currentAppMetadata)
            buildCombinedResult(
                firmwareOutcome = firmwareOutcome,
                appOutcome = appOutcome,
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
        val settings = appSettingsRepository.currentFirmwareUpdateSettings().normalized()
        val currentAppMetadata = currentAppMetadata()
        var workingState = appSettingsRepository.currentFirmwareUpdateCheckState().copy(
            updateInProgress = false,
            updateProgressPercent = 0,
            appUpdateInProgress = false,
            appUpdateProgressPercent = 0,
        )

        if (!settings.isConfigured()) {
            return publishState(
                workingState.copy(
                    statusMessage = "Configurazione HTTP incompleta",
                    appCurrentBuild = currentAppMetadata.build,
                    appCurrentTimestamp = currentAppMetadata.timestamp,
                    appCurrentProtocol = currentAppMetadata.protocol,
                    appAvailability = FirmwareUpdateAvailability.Error,
                    appStatusMessage = "Configurazione HTTP incompleta",
                ),
            )
        }

        return runCatching {
            val remoteManifest = fetchAppManifest(settings)
            val assessment = assessUpdateAvailability(
                current = currentAppMetadata,
                remote = remoteManifest,
                updateMessage = "Nuova build app disponibile",
                currentMessage = "App allineata al manifest remoto",
            )
            val comparisonState = workingState.copy(
                lastCheckedAtEpochMs = System.currentTimeMillis(),
                appCurrentBuild = currentAppMetadata.build,
                appCurrentTimestamp = currentAppMetadata.timestamp,
                appCurrentProtocol = currentAppMetadata.protocol,
                appRemoteBuild = remoteManifest.build,
                appRemoteTimestamp = remoteManifest.timestamp,
                appRemoteProtocol = remoteManifest.protocol,
                appAvailability = assessment.availability,
                appStatusMessage = assessment.statusMessage,
                statusMessage = assessment.statusMessage,
            )

            if (assessment.availability != FirmwareUpdateAvailability.UpdateAvailable) {
                return@runCatching publishState(
                    comparisonState.copy(
                        appUpdateInProgress = false,
                        appUpdateProgressPercent = 0,
                    ),
                )
            }

            if (!canRequestPackageInstalls()) {
                openUnknownSourcesSettings()
                return@runCatching publishState(
                    comparisonState.copy(
                        statusMessage = "Abilita l'installazione app per RideScope e riprova",
                        appStatusMessage = "Abilita l'installazione app per RideScope e riprova",
                        appUpdateInProgress = false,
                        appUpdateProgressPercent = 0,
                    ),
                )
            }

            workingState = publishAppProgress(
                baseState = comparisonState,
                progressPercent = 0,
                statusMessage = "Scaricamento APK remoto...",
            )

            val apkBytes = fetchAppBinary(settings) { bytesRead, totalBytes ->
                val progress = scaledProgress(
                    current = bytesRead,
                    total = totalBytes,
                    basePercent = 0,
                    spanPercent = 90,
                )
                workingState = publishAppProgress(
                    baseState = workingState,
                    progressPercent = progress,
                    statusMessage = formatTransferStatus("Scaricamento APK remoto", bytesRead, totalBytes),
                )
            }

            workingState = publishAppProgress(
                baseState = workingState,
                progressPercent = 95,
                statusMessage = "Preparazione installazione Android...",
            )

            val apkFile = persistAppInstaller(apkBytes)
            launchAppInstaller(apkFile)

            publishState(
                workingState.copy(
                    statusMessage = "Installazione app avviata",
                    appStatusMessage = "APK scaricato, completa l'installazione Android",
                    appUpdateInProgress = false,
                    appUpdateProgressPercent = 100,
                ),
            )
        }.getOrElse { error ->
            publishState(
                workingState.copy(
                    statusMessage = error.message ?: "Aggiornamento app fallito",
                    appAvailability = FirmwareUpdateAvailability.Error,
                    appStatusMessage = error.message ?: "Aggiornamento app fallito",
                    appUpdateInProgress = false,
                    appUpdateProgressPercent = 0,
                ),
            )
        }
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
            appAvailability = FirmwareUpdateAvailability.Unknown,
            appStatusMessage = "Controllo app non eseguito",
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

    private suspend fun fetchAppManifest(settings: FirmwareUpdateSettings): ManifestMetadata {
        val manifestContent = withContext(Dispatchers.IO) {
            downloadFileOverHttp(settings.appManifestRemoteUrl())
        }
        return parseAppManifest(String(manifestContent, StandardCharsets.UTF_8))
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

    private suspend fun fetchAppBinary(
        settings: FirmwareUpdateSettings,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): ByteArray {
        return withContext(Dispatchers.IO) {
            downloadFileOverHttp(
                remoteUrl = settings.appBinaryRemoteUrl(),
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

    private fun parseAppManifest(rawJson: String): ManifestMetadata {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val appRoot = root["ridescope"]?.jsonObject
            ?: error("manifest.json remoto non contiene ridescope{}")
        return ManifestMetadata(
            build = appRoot.stringValue("build"),
            timestamp = appRoot.stringValue("timestamp"),
            protocol = appRoot.stringValue("protocol"),
        ).also { metadata ->
            if (metadata.build.isBlank() || metadata.timestamp.isBlank() || metadata.protocol.isBlank()) {
                error("manifest.json remoto app incompleto")
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

    private suspend fun checkAppAvailability(
        settings: FirmwareUpdateSettings,
        currentAppMetadata: ManifestMetadata,
    ): CheckOutcome {
        val remoteManifest = runCatching { fetchAppManifest(settings) }.getOrElse { error ->
            return CheckOutcome(
                current = currentAppMetadata,
                remote = null,
                availability = FirmwareUpdateAvailability.Error,
                statusMessage = error.message ?: "Download manifest app fallito",
                currentInfo = null,
            )
        }
        val assessment = assessUpdateAvailability(
            current = currentAppMetadata,
            remote = remoteManifest,
            updateMessage = "Nuova build app disponibile",
            currentMessage = "App allineata al manifest remoto",
        )
        return CheckOutcome(
            current = currentAppMetadata,
            remote = remoteManifest,
            availability = assessment.availability,
            statusMessage = assessment.statusMessage,
            currentInfo = null,
        )
    }

    private fun buildCombinedResult(
        firmwareOutcome: CheckOutcome,
        appOutcome: CheckOutcome,
    ): FirmwareUpdateCheckState {
        val overallStatusMessage = when {
            firmwareOutcome.availability == FirmwareUpdateAvailability.UpdateAvailable &&
                appOutcome.availability == FirmwareUpdateAvailability.UpdateAvailable ->
                "Nuovi aggiornamenti disponibili per firmware e app"
            firmwareOutcome.availability == FirmwareUpdateAvailability.UpdateAvailable ->
                "Nuova revisione firmware disponibile"
            appOutcome.availability == FirmwareUpdateAvailability.UpdateAvailable ->
                "Nuova build app disponibile"
            firmwareOutcome.availability == FirmwareUpdateAvailability.Error &&
                appOutcome.availability == FirmwareUpdateAvailability.Error ->
                "Controllo firmware e app fallito"
            firmwareOutcome.availability == FirmwareUpdateAvailability.Error ->
                firmwareOutcome.statusMessage
            appOutcome.availability == FirmwareUpdateAvailability.Error ->
                appOutcome.statusMessage
            firmwareOutcome.availability == FirmwareUpdateAvailability.UpToDate &&
                appOutcome.availability == FirmwareUpdateAvailability.UpToDate ->
                "Firmware e app allineati ai manifest remoti"
            else -> "Controllo aggiornamenti completato"
        }

        return buildResult(
            currentInfo = firmwareOutcome.currentInfo,
            remoteManifest = firmwareOutcome.remote,
            availability = firmwareOutcome.availability,
            statusMessage = overallStatusMessage,
            currentAppMetadata = appOutcome.current,
            remoteAppManifest = appOutcome.remote,
            appAvailability = appOutcome.availability,
            appStatusMessage = appOutcome.statusMessage,
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
            build = BuildConfig.RIDESCOPE_BUILD,
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

    private fun publishAppProgress(
        baseState: FirmwareUpdateCheckState,
        progressPercent: Int,
        statusMessage: String,
    ): FirmwareUpdateCheckState {
        return publishState(
            baseState.copy(
                statusMessage = statusMessage,
                appStatusMessage = statusMessage,
                appUpdateInProgress = true,
                appUpdateProgressPercent = progressPercent.coerceIn(0, 100),
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

    private suspend fun persistAppInstaller(apkBytes: ByteArray): File {
        return withContext(Dispatchers.IO) {
            val updateDir = File(appContext.cacheDir, AppUpdateCacheDirectory)
            if (!updateDir.exists() && !updateDir.mkdirs()) {
                error("Impossibile creare la cache per l'aggiornamento app")
            }
            val apkFile = File(updateDir, AppApkFileName)
            apkFile.outputStream().use { output ->
                output.write(apkBytes)
                output.flush()
            }
            apkFile
        }
    }

    private fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()
    }

    private fun openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    private fun launchAppInstaller(apkFile: File) {
        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, ApkMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(installIntent)
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
        const val ApkMimeType = "application/vnd.android.package-archive"
        const val AppApkFileName = "ridescope.apk"
        const val AppUpdateCacheDirectory = "app_updates"
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
