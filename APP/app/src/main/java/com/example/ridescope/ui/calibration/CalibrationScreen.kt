package com.example.ridescope.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ridescope.data.model.CalibrationStatus
import com.example.ridescope.data.model.ReferenceStatus
import com.example.ridescope.data.repository.TelemetryRepository
import com.example.ridescope.ui.common.RideScopeCardTitle
import com.example.ridescope.ui.common.RideScopePageTitle
import com.example.ridescope.ui.common.RideScopePopupHost
import com.example.ridescope.ui.common.RideScopePopupType
import com.example.ridescope.ui.common.showRideScopePopup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun CalibrationScreen(
    repository: TelemetryRepository,
    paddingValues: PaddingValues,
    pageMenu: (@Composable () -> Unit)? = null,
    pageHomeButton: (@Composable () -> Unit)? = null,
) {
    var calibration by remember { mutableStateOf(CalibrationStatus()) }
    var reference by remember { mutableStateOf(ReferenceStatus()) }
    var calibrationProgressVisible by remember { mutableStateOf(false) }
    var calibrationResetInProgress by remember { mutableStateOf(false) }
    var zeroResetInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun showPopupAsync(
        message: String,
        type: RideScopePopupType,
    ) {
        scope.launch {
            snackbarHostState.showRideScopePopup(
                message = message,
                type = type,
            )
        }
    }

    suspend fun refresh(showPopup: Boolean = true) {
        var calibrationError: String? = null
        var referenceError: String? = null

        runCatching { repository.readCalibration() }
            .onSuccess { calibration = it }
            .onFailure { calibrationError = "Errore calibrazione: ${it.message}" }

        runCatching { repository.readReference() }
            .onSuccess { reference = it }
            .onFailure { referenceError = "Errore riferimento: ${it.message}" }

        if (!showPopup) {
            return
        }

        val errors = listOfNotNull(calibrationError, referenceError)
        when (errors.size) {
            0 -> Unit
            1 -> snackbarHostState.showRideScopePopup(
                message = errors.first(),
                type = RideScopePopupType.Warning,
            )
            else -> snackbarHostState.showRideScopePopup(
                message = errors.joinToString(" | "),
                type = RideScopePopupType.Error,
            )
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    suspend fun monitorCalibrationProgress() {
        calibrationProgressVisible = true
        var observedRunning = false
        repeat(50) {
            delay(if (observedRunning) 250 else 150)
            refresh(showPopup = false)
            if (calibration.running) {
                observedRunning = true
            } else if (observedRunning) {
                calibrationProgressVisible = false
                refresh(showPopup = false)
                return
            }
        }
        calibrationProgressVisible = false
    }

    val calibrationBusy = calibrationProgressVisible || calibration.running || calibrationResetInProgress
    val zeroBusy = zeroResetInProgress
    val calibrationChipValid = calibration.valid && !calibrationBusy
    val zeroChipValid = reference.valid && !zeroBusy

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
                title = "Calibrazione",
                menuButton = pageMenu,
                trailingAction = pageHomeButton,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val result = runCatching { repository.runCalibration() }
                            if (result.isSuccess) {
                                showPopupAsync(
                                    message = "Calibrazione avviata",
                                    type = RideScopePopupType.Warning,
                                )
                                monitorCalibrationProgress()
                            } else {
                                calibrationProgressVisible = false
                                showPopupAsync(
                                    message = "Errore avvio calibrazione: ${result.exceptionOrNull()?.message}",
                                    type = RideScopePopupType.Error,
                                )
                            }
                        }
                    },
                    enabled = !calibrationBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("AVVIA CALIB")
                }

                Button(
                    onClick = {
                        scope.launch {
                            calibrationResetInProgress = true
                            val result = runCatching { repository.clearCalibration() }
                            refresh()
                            calibrationResetInProgress = false
                            result
                                .onSuccess { }
                                .onFailure {
                                    showPopupAsync(
                                        message = "Errore reset calibrazione: ${it.message}",
                                        type = RideScopePopupType.Error,
                                    )
                                }
                        }
                    },
                    enabled = calibration.valid && !calibrationBusy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("RESET CALIB")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val result = runCatching { repository.zeroReference() }
                            refresh()
                            result
                                .onSuccess { }
                                .onFailure {
                                    showPopupAsync(
                                        message = "Errore impostazione zero: ${it.message}",
                                        type = RideScopePopupType.Error,
                                    )
                                }
                        }
                    },
                    enabled = !zeroBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("IMPOSTA ZERO")
                }

                Button(
                    onClick = {
                        scope.launch {
                            zeroResetInProgress = true
                            val result = runCatching { repository.clearReference() }
                            refresh()
                            zeroResetInProgress = false
                            result
                                .onSuccess { }
                                .onFailure {
                                    showPopupAsync(
                                        message = "Errore reset zero: ${it.message}",
                                        type = RideScopePopupType.Error,
                                    )
                                }
                        }
                    },
                    enabled = reference.valid && !zeroBusy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("RESET ZERO")
                }
            }

            if (calibrationProgressVisible || calibration.running) {
                if (calibration.running) {
                    LinearProgressIndicator(
                        progress = { (calibration.progressPercent.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedCard(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DataColumnTitle(
                            title = "Calib",
                            valid = calibrationChipValid,
                        )
                        LabeledValue("Acc X", formatSigned(calibration.accOffsetX))
                        LabeledValue("Acc Y", formatSigned(calibration.accOffsetY))
                        LabeledValue("Acc Z", formatSigned(calibration.accOffsetZ))
                        LabeledValue("Gyro X", formatSigned(calibration.gyroOffsetXDps, "dps"))
                        LabeledValue("Gyro Y", formatSigned(calibration.gyroOffsetYDps, "dps"))
                        LabeledValue("Gyro Z", formatSigned(calibration.gyroOffsetZDps, "dps"))
                    }
                }

                OutlinedCard(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DataColumnTitle(
                            title = "Zero",
                            valid = zeroChipValid,
                        )
                        LabeledValue("Roll zero", formatSigned(reference.rollZeroDeg, "deg"))
                        LabeledValue("Pitch zero", formatSigned(reference.pitchZeroDeg, "deg"))
                    }
                }
            }
        }

        RideScopePopupHost(hostState = snackbarHostState)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DataColumnTitle(title: String, valid: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RideScopeCardTitle(title = title)
        ValidityChip(valid = valid)
    }
}

@Composable
private fun ValidityChip(valid: Boolean) {
    val containerColor = if (valid) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
    val contentColor = if (valid) Color.White else MaterialTheme.colorScheme.onError
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = if (valid) "OK" else "NO",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun formatSigned(value: Double?, unit: String = "g"): String {
    if (value == null) return "-"
    return String.format(Locale.US, "%+.3f %s", value, unit)
}
