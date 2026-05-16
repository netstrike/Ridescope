package com.example.ridescope.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ridescope.data.model.RecordingStatus
import com.example.ridescope.data.model.TelemetryGpsSource
import com.example.ridescope.data.model.TelemetryUiState
import com.example.ridescope.ui.common.RideScopeTitleInputDialog
import com.example.ridescope.ui.theme.RideScopeTheme

private val locationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private enum class RecordingActionConfirmation {
    Start,
    Resume,
    Stop,
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    paddingValues: PaddingValues,
    pageMenu: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var hasLocationPermission by remember(context) {
        mutableStateOf(checkLocationPermission(context))
    }
    var pendingRecordingAction by remember { mutableStateOf<RecordingActionConfirmation?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(hasLocationPermission) {
        viewModel.setGpsPermissionGranted(hasLocationPermission)
    }

    TelemetryScreenContent(
        state = uiState,
        paddingValues = paddingValues,
        pageMenu = pageMenu,
        showPhoneGpsPermissionCta = !uiState.gpsPermissionGranted && uiState.gpsSource != TelemetryGpsSource.Firmware,
        onRequestPhoneGpsPermission = { permissionLauncher.launch(locationPermissions) },
        onStartOrResumeRecording = {
            pendingRecordingAction = when (uiState.recording.status) {
                RecordingStatus.Paused -> RecordingActionConfirmation.Resume
                else -> RecordingActionConfirmation.Start
            }
        },
        onPauseRecording = viewModel::pauseRecording,
        onStopRecording = { pendingRecordingAction = RecordingActionConfirmation.Stop },
    )

    pendingRecordingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingRecordingAction = null },
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black,
            title = {
                Text(
                    text = when (action) {
                        RecordingActionConfirmation.Start -> "Conferma avvio recording"
                        RecordingActionConfirmation.Resume -> "Conferma ripresa recording"
                        RecordingActionConfirmation.Stop -> "Conferma stop recording"
                    },
                    color = Color.Black,
                )
            },
            text = {
                Text(
                    text = when (action) {
                        RecordingActionConfirmation.Start -> "Vuoi avviare una nuova registrazione?"
                        RecordingActionConfirmation.Resume -> "Vuoi riprendere la registrazione in pausa?"
                        RecordingActionConfirmation.Stop -> "Vuoi fermare la registrazione corrente?"
                    },
                    color = Color.Black,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRecordingAction = null
                        when (action) {
                            RecordingActionConfirmation.Start,
                            RecordingActionConfirmation.Resume -> viewModel.startOrResumeRecording()
                            RecordingActionConfirmation.Stop -> viewModel.stopRecording()
                        }
                    }
                ) {
                    Text("CONFERMA")
                }
            },
            dismissButton = {
                Button(
                    onClick = { pendingRecordingAction = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE26C6C),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("ANNULLA")
                }
            },
        )
    }

    if (uiState.recording.status == RecordingStatus.AwaitingSaveName) {
        RideScopeTitleInputDialog(
            title = "Salva registrazione",
            value = uiState.recording.titleDraft,
            onValueChange = viewModel::updateRecordingTitleDraft,
            onConfirm = viewModel::saveRecordingFile,
            onDismiss = viewModel::cancelRecordingSave,
            onDismissRequest = {},
        )
    }
}

@Composable
internal fun TelemetryScreenContent(
    state: TelemetryUiState,
    paddingValues: PaddingValues,
    pageMenu: (@Composable () -> Unit)? = null,
    showPhoneGpsPermissionCta: Boolean,
    onRequestPhoneGpsPermission: () -> Unit,
    onStartOrResumeRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawCarbonBackground() }
            .padding(paddingValues)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TelemetryDashboardWidget(
            state = state,
            pageMenu = pageMenu,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        if (showPhoneGpsPermissionCta) {
            Button(
                onClick = onRequestPhoneGpsPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("ABILITA GPS TELEFONO")
            }
        }
        RecordingModuleCard(
            state = state,
            onStartOrResume = onStartOrResumeRecording,
            onPause = onPauseRecording,
            onStop = onStopRecording,
        )
    }
}

@Composable
private fun RecordingModuleCard(
    state: TelemetryUiState,
    onStartOrResume: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val recording = state.recording
    val canStartOrResume = recording.status == RecordingStatus.Idle || recording.status == RecordingStatus.Paused
    val canPause = recording.status == RecordingStatus.Recording
    val canStop = recording.status == RecordingStatus.Recording || recording.status == RecordingStatus.Paused

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        border = BorderStroke(0.dp, Color.Transparent),
        colors = CardDefaults.outlinedCardColors(
            containerColor = Color.Transparent,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStartOrResume,
                enabled = canStartOrResume,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.RadioButtonChecked,
                    contentDescription = "Avvia recording",
                )
            }

            OutlinedButton(
                onClick = onPause,
                enabled = canPause,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Pause,
                    contentDescription = "Metti in pausa recording",
                )
            }

            Button(
                onClick = onStop,
                enabled = canStop,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Stop,
                    contentDescription = "Ferma recording",
                )
            }
        }
    }
}

private fun checkLocationPermission(context: Context): Boolean {
    return locationPermissions.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

@Preview(name = "Telemetry Screen", group = "Telemetry", showBackground = true, backgroundColor = 0xFF090B0F, widthDp = 412, heightDp = 915)
@Composable
private fun TelemetryScreenContentPreview() {
    RideScopeTheme(darkTheme = true) {
        TelemetryScreenContent(
            state = TelemetryPreviewState,
            paddingValues = PaddingValues(0.dp),
            showPhoneGpsPermissionCta = false,
            onRequestPhoneGpsPermission = {},
            onStartOrResumeRecording = {},
            onPauseRecording = {},
            onStopRecording = {},
        )
    }
}
