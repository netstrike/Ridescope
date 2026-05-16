package com.example.ridescope.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ridescope.data.model.FirmwareStatus
import com.example.ridescope.data.model.TelemetryGpsSource
import com.example.ridescope.data.repository.TelemetryRepository
import com.example.ridescope.ui.common.RideScopeCardTitle
import com.example.ridescope.ui.common.RideScopePageTitle
import com.example.ridescope.ui.common.RideScopePopupHost
import com.example.ridescope.ui.common.RideScopePopupType
import com.example.ridescope.ui.common.showRideScopePopup
import com.example.ridescope.ui.home.HomeViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    repository: TelemetryRepository,
    viewModel: HomeViewModel,
    paddingValues: PaddingValues,
    pageMenu: (@Composable () -> Unit)? = null,
    pageHomeButton: (@Composable () -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    var firmwareStatus by remember { mutableStateOf<FirmwareStatus?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    suspend fun refreshDiagnostics() {
        runCatching {
            repository.readStatus()
        }
            .onSuccess {
                firmwareStatus = it
            }
            .onFailure {
                snackbarHostState.showRideScopePopup(
                    message = "Errore status firmware: ${it.message}",
                    type = RideScopePopupType.Error,
                )
            }
    }

    LaunchedEffect(Unit) {
        refreshDiagnostics()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(top = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RideScopePageTitle(
                title = "Diagnostica",
                menuButton = pageMenu,
                trailingAction = pageHomeButton,
            )

            Button(
                onClick = { scope.launch { refreshDiagnostics() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("AGGIORNA STATUS")
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RideScopeCardTitle(title = "Stream live app")
                    DiagnosticLine("BLE app", yesNo(state.bleConnected))
                    DiagnosticLine("BLE autenticato", yesNo(state.bleAuthenticated))
                    DiagnosticLine("Live notify app", yesNo(state.liveSubscribed))
                    DiagnosticLine("IMU telemetria", yesNo(state.imuReady))
                    DiagnosticLine("IMU ts tick", formatLong(state.imuTimestampTicks))
                    DiagnosticLine("IMU temp", formatOptional(state.imuTemperatureC, "C"))
                    DiagnosticLine("GPS sorgente app", formatGpsSource(state.gpsSource))
                    DiagnosticLine("GPS fix app", yesNo(state.gpsFixAvailable))
                    DiagnosticLine("GPS lat", formatCoordinate(state.gpsLatitudeDeg))
                    DiagnosticLine("GPS lon", formatCoordinate(state.gpsLongitudeDeg))
                    DiagnosticLine("GPS heading", formatOptional(state.gpsHeadingDeg, "deg"))
                    DiagnosticLine("GPS speed", formatOptional(state.gpsSpeedKmh, "km/h"))
                    DiagnosticLine("GPS quota", formatOptional(state.gpsAltitudeMeters, "m"))
                    DiagnosticLine("GPS accuratezza", formatOptional(state.gpsAccuracyMeters, "m"))
                    DiagnosticLine("Raw roll", formatSigned(state.rawRollDeg, "deg"))
                    DiagnosticLine("Raw pitch", formatSigned(state.rawPitchDeg, "deg"))
                    Text("Ultimo frame JSON", style = MaterialTheme.typography.titleSmall)
                    Text(state.rawFrame.ifBlank { "Nessun frame ricevuto" })
                }
            }

            firmwareStatus?.let { status ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RideScopeCardTitle(title = "Trasporto firmware")
                        DiagnosticLine("Kind", status.transport.kind)
                        DiagnosticLine("BLE ready", yesNo(status.transport.bleReady))
                        DiagnosticLine("Advertising", yesNo(status.transport.bleAdvertising))
                        DiagnosticLine("Client connesso", yesNo(status.transport.bleClientConnected))
                        DiagnosticLine("Autenticato", yesNo(status.transport.bleAuthenticated))
                        DiagnosticLine("Notify response", yesNo(status.transport.bleResponseSubscribed))
                        DiagnosticLine("Notify live", yesNo(status.transport.bleLiveSubscribed))
                        DiagnosticLine("MTU", status.transport.bleMtu.toString())
                        DiagnosticLine("OTA BLE", yesNo(status.transport.otaBleEnabled))
                        DiagnosticLine("OTA busy", yesNo(status.transport.otaBusy))
                        DiagnosticLine("OTA progress", "${status.transport.otaProgressPercent}%")
                    }
                }

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RideScopeCardTitle(title = "Stato firmware")
                        DiagnosticLine("IMU pronta", yesNo(status.imuReady == true))
                        DiagnosticLine("Calibrazione valida", yesNo(status.calibrationValid))
                        DiagnosticLine("Calibrazione in corso", yesNo(status.calibrationRunning))
                        DiagnosticLine("Progress calibrazione", "${status.calibrationProgressPercent}%")
                        DiagnosticLine("Riferimento valido", yesNo(status.referenceValid))
                        DiagnosticLine("GNSS presente", yesNo(status.gpsPresent))
                        DiagnosticLine("GNSS configurato", yesNo(status.gpsConfigured))
                        DiagnosticLine("GNSS streaming", yesNo(status.gpsStreaming))
                        DiagnosticLine("GNSS usabile", yesNo(status.gpsUsable))
                        DiagnosticLine("Fix type", status.gpsFixType.toString())
                        DiagnosticLine("Satelliti", status.gpsNumSv.toString())
                        DiagnosticLine("Eta fix", "${status.gpsFixAgeMs} ms")
                        DiagnosticLine("I2C address", status.i2cAddress ?: "-")
                        DiagnosticLine("Data ready mode", status.dataReadyMode ?: "-")
                        DiagnosticLine("Data ready now", yesNo(status.dataReadyNow))
                        DiagnosticLine("Uptime", formatUptime(status.uptimeMs))
                    }
                }

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RideScopeCardTitle(title = "Filtri")
                        DiagnosticLine("Complementary", yesNo(status.filters.complementaryEnabled))
                        DiagnosticLine("Accel LPF", yesNo(status.filters.accelLpfEnabled))
                        DiagnosticLine("Gyro LPF", yesNo(status.filters.gyroLpfEnabled))
                        DiagnosticLine("Adaptive trust", yesNo(status.filters.adaptiveAccelTrustEnabled))
                    }
                }
            }
        }

        RideScopePopupHost(hostState = snackbarHostState)
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyLarge)
}

private fun yesNo(value: Boolean): String = if (value) "si" else "no"

private fun formatSigned(value: Double, unit: String = "g"): String =
    String.format(Locale.US, "%+.3f %s", value, unit)

private fun formatOptional(value: Double?, unit: String, decimals: Int = 2): String =
    value?.let { String.format(Locale.US, "%.${decimals}f %s", it, unit) } ?: "-"

private fun formatCoordinate(value: Double?): String =
    value?.let { String.format(Locale.US, "%.7f", it) } ?: "-"

private fun formatLong(value: Long?): String = value?.toString() ?: "-"

private fun formatUptime(uptimeMs: Long): String =
    String.format(Locale.US, "%.1f s", uptimeMs / 1000.0)

private fun formatGpsSource(source: TelemetryGpsSource): String = when (source) {
    TelemetryGpsSource.None -> "-"
    TelemetryGpsSource.Firmware -> "firmware"
    TelemetryGpsSource.Phone -> "telefono"
}
