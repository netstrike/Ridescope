package com.example.ridescope.ui.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ridescope.RecordingForegroundService
import com.example.ridescope.data.model.PhoneLocationSnapshot
import com.example.ridescope.data.model.RecordingPeaks
import com.example.ridescope.data.model.RecordingStatus
import com.example.ridescope.data.model.RecordingUiState
import com.example.ridescope.data.model.TelemetryGpsSource
import com.example.ridescope.data.model.TelemetryUiState
import com.example.ridescope.data.repository.AppSettingsRepository
import com.example.ridescope.data.repository.DevicePowerRepository
import com.example.ridescope.data.repository.PhoneSensorRepository
import com.example.ridescope.data.repository.RecordingSample
import com.example.ridescope.data.repository.RecordingSummary
import com.example.ridescope.data.repository.RideRecordingRepository
import com.example.ridescope.data.repository.TelemetryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class HomeViewModel(
    private val repository: TelemetryRepository,
    private val phoneSensorRepository: PhoneSensorRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val devicePowerRepository: DevicePowerRepository,
    private val rideRecordingRepository: RideRecordingRepository,
) : ViewModel() {

    private val firmwareState = MutableStateFlow(TelemetryUiState(bleReconnecting = true))
    private val phoneState = MutableStateFlow(PhoneLocationSnapshot())
    private val telemetryBaseState = MutableStateFlow(TelemetryUiState(bleReconnecting = true))
    private val recordingState = MutableStateFlow(RecordingUiState())

    val uiState: StateFlow<TelemetryUiState> = combine(telemetryBaseState, recordingState) { telemetry, recording ->
        telemetry.copy(recording = recording)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TelemetryUiState(bleReconnecting = true))

    private val sampleChannel = Channel<Pair<RideRecordingRepository.Session, RecordingSample>>(Channel.UNLIMITED)

    private var firmwareJob: Job? = null
    private var gpsJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var activeRecordingSession: RideRecordingRepository.Session? = null
    private var lastRecordingTickElapsedMs: Long? = null

    init {
        observeTelemetryBaseState()
        observeRecordingProcessingState()
        reconnect()
        viewModelScope.launch {
            for ((session, sample) in sampleChannel) {
                rideRecordingRepository.appendSample(session, sample)
            }
        }
    }

    fun reconnect() {
        firmwareJob?.cancel()
        firmwareJob = viewModelScope.launch {
            val initialState = firmwareState.value.copy(
                bleConnected = false,
                bleReconnecting = true,
                liveSubscribed = false,
            )
            firmwareState.value = initialState
            runCatching {
                repository.connectLive(initialState = initialState).collect { state ->
                    firmwareState.value = state
                }
            }.onFailure { error ->
                firmwareState.value = firmwareState.value.copy(
                    bleConnected = false,
                    bleReconnecting = true,
                    liveSubscribed = false,
                    rawFrame = error.message ?: firmwareState.value.rawFrame,
                )
            }
        }
    }

    fun setGpsPermissionGranted(granted: Boolean) {
        if (!granted) {
            gpsJob?.cancel()
            gpsJob = null
            phoneState.value = PhoneLocationSnapshot()
            return
        }

        if (gpsJob != null) {
            return
        }

        gpsJob = viewModelScope.launch {
            phoneSensorRepository.gpsTelemetry().collect { snapshot ->
                phoneState.value = snapshot
            }
        }
    }

    fun startOrResumeRecording() {
        when (recordingState.value.status) {
            RecordingStatus.Idle -> startRecording()
            RecordingStatus.Paused -> resumeRecording()
            else -> Unit
        }
    }

    fun pauseRecording() {
        if (recordingState.value.status != RecordingStatus.Recording) {
            return
        }
        finalizeRecordingInterval()
        updateRecordingStatus(RecordingStatus.Paused)
    }

    fun stopRecording() {
        val status = recordingState.value.status
        if (status != RecordingStatus.Recording && status != RecordingStatus.Paused) {
            return
        }
        finalizeRecordingInterval()
        recordingState.value = recordingState.value.copy(
            status = RecordingStatus.AwaitingSaveName,
            stoppedAtEpochMs = System.currentTimeMillis(),
        )
        RecordingForegroundService.notifyRecordingStatus(RecordingStatus.AwaitingSaveName)
    }

    private fun finalizeRecordingInterval() {
        accumulateRecordingDurations()
        stopRecordingTimer()
    }

    private fun updateRecordingStatus(status: RecordingStatus) {
        recordingState.value = recordingState.value.copy(status = status)
        RecordingForegroundService.notifyRecordingStatus(status)
    }

    fun updateRecordingTitleDraft(value: String) {
        recordingState.value = recordingState.value.copy(titleDraft = value)
    }

    fun saveRecordingFile() {
        val session = activeRecordingSession ?: return
        val currentState = recordingState.value

        viewModelScope.launch {
            runCatching {
                rideRecordingRepository.finalizeSession(
                    session = session,
                    summary = currentState.toRecordingSummary(),
                )
            }.onSuccess {
                activeRecordingSession = null
                recordingState.value = RecordingUiState()
                RecordingForegroundService.notifyRecordingStatus(RecordingStatus.Idle)
            }
        }
    }

    fun cancelRecordingSave() {
        val session = activeRecordingSession
        if (session == null) {
            recordingState.value = RecordingUiState()
            return
        }

        viewModelScope.launch {
            runCatching {
                rideRecordingRepository.discardSession(session)
            }
            activeRecordingSession = null
            recordingState.value = RecordingUiState()
            RecordingForegroundService.notifyRecordingStatus(RecordingStatus.Idle)
        }
    }

    private fun startRecording() {
        val startedAtEpochMs = System.currentTimeMillis()
        val outputFileName = "${LocalDateTime.now().format(RecordingFileNameFormatter)}.xml"
        val initialState = RecordingUiState(
            status = RecordingStatus.Recording,
            startedAtEpochMs = startedAtEpochMs,
            startTripKm = telemetryBaseState.value.gpsTripKm,
            outputFileName = outputFileName,
            titleDraft = DefaultRecordingTitle,
        )
        recordingState.value = initialState
        RecordingForegroundService.notifyRecordingStatus(RecordingStatus.Recording)
        lastRecordingTickElapsedMs = SystemClock.elapsedRealtime()
        startRecordingTimer()

        viewModelScope.launch {
            activeRecordingSession = rideRecordingRepository.startSession(
                outputFileName = outputFileName,
                startedAtEpochMs = startedAtEpochMs,
            )
            handleRecordingTelemetry(telemetryBaseState.value)
        }
    }

    private fun resumeRecording() {
        updateRecordingStatus(RecordingStatus.Recording)
        lastRecordingTickElapsedMs = SystemClock.elapsedRealtime()
        startRecordingTimer()
        handleRecordingTelemetry(telemetryBaseState.value)
    }

    private fun observeTelemetryBaseState() {
        viewModelScope.launch {
            combine(firmwareState, phoneState, appSettingsRepository.telemetryGaugeConfig) { firmware, phone, gaugeConfig ->
                val useFirmwareGps = firmware.gpsSource == TelemetryGpsSource.Firmware && firmware.gpsFixAvailable
                firmware.copy(
                    gpsPermissionGranted = useFirmwareGps || phone.permissionGranted,
                    gpsSource = when {
                        useFirmwareGps -> TelemetryGpsSource.Firmware
                        phone.permissionGranted -> TelemetryGpsSource.Phone
                        else -> TelemetryGpsSource.None
                    },
                    gpsFixAvailable = if (useFirmwareGps) firmware.gpsFixAvailable else phone.fixAvailable,
                    gpsUsingNetworkProvider = if (useFirmwareGps) false else phone.usingNetworkProvider,
                    gpsSampleTimestampMs = if (useFirmwareGps) firmware.gpsSampleTimestampMs else null,
                    gpsLatitudeDeg = if (useFirmwareGps) firmware.gpsLatitudeDeg else phone.latitudeDeg,
                    gpsLongitudeDeg = if (useFirmwareGps) firmware.gpsLongitudeDeg else phone.longitudeDeg,
                    gpsHeadingDeg = if (useFirmwareGps) firmware.gpsHeadingDeg else phone.headingDeg,
                    gpsSpeedKmh = if (useFirmwareGps) firmware.gpsSpeedKmh else phone.speedKmh,
                    gpsMaxSpeedKmh = if (useFirmwareGps) firmware.gpsMaxSpeedKmh else phone.maxSpeedKmh,
                    gpsTripKm = if (useFirmwareGps) firmware.gpsTripKm else phone.tripKm,
                    gpsAltitudeMeters = if (useFirmwareGps) firmware.gpsAltitudeMeters else phone.altitudeMeters,
                    gpsAccuracyMeters = if (useFirmwareGps) firmware.gpsAccuracyMeters else phone.accuracyMeters,
                    gaugeConfig = gaugeConfig,
                )
            }.collect { telemetry ->
                telemetryBaseState.value = telemetry
                handleRecordingTelemetry(telemetry)
            }
        }
    }

    private fun observeRecordingProcessingState() {
        viewModelScope.launch {
            combine(recordingState, appSettingsRepository.keepScreenOnEnabled) { recording, keepScreenOnEnabled ->
                // Wake lock attivo se si sta registrando e keepScreenOn è disabilitato
                // (quando keepScreenOn è attivo, il display resta acceso e non serve il wake lock CPU)
                recording.status == RecordingStatus.Recording && !keepScreenOnEnabled
            }.collect { shouldKeepProcessingActive ->
                devicePowerRepository.setRecordingProcessingEnabled(shouldKeepProcessingActive)
            }
        }
    }

    private fun handleRecordingTelemetry(telemetry: TelemetryUiState) {
        val currentRecording = recordingState.value
        if (currentRecording.status != RecordingStatus.Recording) {
            return
        }

        val updatedState = currentRecording.copy(
            tripKm = currentRecording.resolveTripKm(telemetry.gpsTripKm),
            peaks = currentRecording.peaks.updateWith(telemetry),
        )
        if (updatedState != currentRecording) {
            recordingState.value = updatedState
        }

        val session = activeRecordingSession ?: return
        val sample = RecordingSample(
            timestampEpochMs = System.currentTimeMillis(),
            recordingElapsedMs = updatedState.totalDurationMs,
            travelElapsedMs = updatedState.travelDurationMs,
            tripKm = updatedState.tripKm,
            latitudeDeg = telemetry.gpsLatitudeDeg,
            longitudeDeg = telemetry.gpsLongitudeDeg,
            speedKmh = telemetry.gpsSpeedKmh,
            altitudeMeters = telemetry.gpsAltitudeMeters,
            rollDeg = telemetry.rollDeg,
            pitchDeg = telemetry.pitchDeg,
            accelG = telemetry.longitudinalAccelG.coerceAtLeast(0.0),
            decelG = (-telemetry.longitudinalAccelG).coerceAtLeast(0.0),
        )
        sampleChannel.trySend(session to sample)
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(250)
                accumulateRecordingDurations()
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        lastRecordingTickElapsedMs = null
    }

    private fun accumulateRecordingDurations() {
        val currentState = recordingState.value
        if (currentState.status != RecordingStatus.Recording) {
            lastRecordingTickElapsedMs = SystemClock.elapsedRealtime()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val lastTick = lastRecordingTickElapsedMs ?: now.also {
            lastRecordingTickElapsedMs = it
        }
        val deltaMs = (now - lastTick).coerceAtLeast(0L)
        if (deltaMs == 0L) {
            return
        }

        val travelDelta = if (telemetryBaseState.value.gpsSpeedKmh > 0.0) deltaMs else 0L
        recordingState.value = currentState.copy(
            totalDurationMs = currentState.totalDurationMs + deltaMs,
            travelDurationMs = currentState.travelDurationMs + travelDelta,
            tripKm = currentState.resolveTripKm(telemetryBaseState.value.gpsTripKm),
        )
        lastRecordingTickElapsedMs = now
    }

    private fun RecordingPeaks.updateWith(telemetry: TelemetryUiState): RecordingPeaks {
        return copy(
            speedKmh = maxOf(speedKmh, telemetry.gpsSpeedKmh),
            altitudeMeters = maxOf(altitudeMeters, telemetry.gpsAltitudeMeters ?: 0.0),
            rollDeg = maxOf(rollDeg, abs(telemetry.rollDeg)),
            pitchDeg = maxOf(pitchDeg, telemetry.pitchDeg.coerceAtLeast(0.0)),
            accelG = maxOf(accelG, telemetry.longitudinalAccelG.coerceAtLeast(0.0)),
            decelG = maxOf(decelG, (-telemetry.longitudinalAccelG).coerceAtLeast(0.0)),
        )
    }

    private fun RecordingUiState.resolveTripKm(currentGpsTripKm: Double): Double {
        val baseTripKm = startTripKm ?: currentGpsTripKm
        return (currentGpsTripKm - baseTripKm).coerceAtLeast(0.0)
    }

    private fun RecordingUiState.toRecordingSummary(): RecordingSummary {
        return RecordingSummary(
            title = titleDraft.ifBlank { DefaultRecordingTitle },
            startedAtEpochMs = startedAtEpochMs ?: System.currentTimeMillis(),
            stoppedAtEpochMs = stoppedAtEpochMs ?: System.currentTimeMillis(),
            totalDurationMs = totalDurationMs,
            travelDurationMs = travelDurationMs,
            tripKm = tripKm,
            maxSpeedKmh = peaks.speedKmh,
            maxAltitudeMeters = peaks.altitudeMeters,
            maxRollDeg = peaks.rollDeg,
            maxPitchDeg = peaks.pitchDeg,
            maxAccelG = peaks.accelG,
            maxDecelG = peaks.decelG,
        )
    }

    private companion object {
        const val DefaultRecordingTitle = "Nessun titolo"
        val RecordingFileNameFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("'rs'yyyyMMdd_HHmmss")
    }

    override fun onCleared() {
        devicePowerRepository.releaseAll()
        super.onCleared()
    }
}
