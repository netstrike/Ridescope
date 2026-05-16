package com.example.ridescope.ui.recordings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.ridescope.data.repository.RecordingExportFormat
import com.example.ridescope.data.repository.SavedRecordingFile
import com.example.ridescope.ui.common.RideScopeCardTitle
import com.example.ridescope.ui.common.RideScopePageTitle
import com.example.ridescope.ui.common.RideScopePopupHost
import com.example.ridescope.ui.common.RideScopePopupType
import com.example.ridescope.ui.common.RideScopeTitleInputDialog
import com.example.ridescope.ui.common.showRideScopePopup
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DefaultRecordingTitle = "Nessun titolo"

@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel,
    paddingValues: PaddingValues,
    pageMenu: (@Composable () -> Unit)? = null,
    pageHomeButton: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var pendingDeleteFile by remember { mutableStateOf<SavedRecordingFile?>(null) }
    var pendingEditFile by remember { mutableStateOf<SavedRecordingFile?>(null) }
    var editTitleDraft by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refreshRecordings()
    }

    LaunchedEffect(viewModel, context, snackbarHostState) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordingsUiEvent.ShareExport -> {
                    val contentUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        event.exportedFile.file,
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = event.exportedFile.mimeType
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Esporta registrazione"))
                }

                is RecordingsUiEvent.ShowError -> {
                    snackbarHostState.showRideScopePopup(
                        message = event.message,
                        type = RideScopePopupType.Error,
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(top = 16.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RideScopePageTitle(
                    title = "Registrazioni",
                    menuButton = pageMenu,
                    trailingAction = pageHomeButton,
                )
            }

            when {
                uiState.isLoading -> {
                    item {
                        Text(
                            "Caricamento registrazioni salvate...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        )
                    }
                }

                uiState.files.isEmpty() -> {
                    item {
                        Text(
                            "Nessuna registrazione salvata.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                else -> {
                    items(
                        items = uiState.files,
                        key = { it.fileName },
                    ) { file ->
                        RecordingFileCard(
                            file = file,
                            onExportCsv = { viewModel.exportRecording(file.fileName, RecordingExportFormat.Csv) },
                            onExportGpx = { viewModel.exportRecording(file.fileName, RecordingExportFormat.Gpx) },
                            onExportFit = { viewModel.exportRecording(file.fileName, RecordingExportFormat.Fit) },
                            onEdit = {
                                pendingEditFile = file
                                editTitleDraft = file.title.ifBlank { DefaultRecordingTitle }
                            },
                            onDelete = { pendingDeleteFile = file },
                        )
                    }
                }
            }
        }

        RideScopePopupHost(hostState = snackbarHostState)
    }

    pendingDeleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black,
            title = { Text("Conferma eliminazione", color = Color.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = file.title.ifBlank { DefaultRecordingTitle },
                        color = Color.Black,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatRecordingTimestamp(file.modifiedAtEpochMs),
                        color = Color.Black,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteRecording(file.fileName)
                        pendingDeleteFile = null
                    },
                ) {
                    Text("ELIMINA")
                }
            },
            dismissButton = {
                Button(
                    onClick = { pendingDeleteFile = null },
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

    pendingEditFile?.let { file ->
        RideScopeTitleInputDialog(
            title = "Modifica titolo",
            value = editTitleDraft,
            onValueChange = { editTitleDraft = it },
            onConfirm = {
                viewModel.updateRecordingTitle(file.fileName, editTitleDraft)
                pendingEditFile = null
            },
            onDismiss = { pendingEditFile = null },
        )
    }
}

@Composable
private fun RecordingFileCard(
    file: SavedRecordingFile,
    onExportCsv: () -> Unit,
    onExportGpx: () -> Unit,
    onExportFit: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    RideScopeCardTitle(title = file.title.ifBlank { DefaultRecordingTitle })
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatRecordingTimestamp(file.modifiedAtEpochMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
                        )
                        Text(
                            text = "(${formatFileSize(file.sizeBytes)})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecordingInfoCell(
                    label = "Tempo viaggio",
                    value = formatDuration(file.travelDurationMs),
                    modifier = Modifier.weight(1f),
                )
                RecordingInfoCell(
                    label = "Distanza",
                    value = formatTrip(file.tripKm),
                    modifier = Modifier.weight(1f),
                )
                RecordingInfoCell(
                    label = "Roll max",
                    value = formatAngle(file.maxRollDeg),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecordingInfoCell(
                    label = "Pitch max",
                    value = formatAngle(file.maxPitchDeg),
                    modifier = Modifier.weight(1f),
                )
                RecordingInfoCell(
                    label = "Acc / Dec max",
                    value = formatAccelDec(file.maxAccelG, file.maxDecelG),
                    modifier = Modifier.weight(2f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onExportCsv) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = "Esporta CSV ${file.fileName}",
                            tint = Color(0xFF6CDB48),
                        )
                    }
                    IconButton(onClick = onExportGpx) {
                        Icon(
                            imageVector = Icons.Outlined.Route,
                            contentDescription = "Esporta GPX ${file.fileName}",
                            tint = Color(0xFF3AAEFF),
                        )
                    }
                    IconButton(
                        onClick = onExportFit,
                        modifier = Modifier.semantics {
                            contentDescription = "Esporta FIT ${file.fileName}"
                        },
                    ) {
                        Text(
                            text = "FIT",
                            color = Color(0xFFFFB347),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Modifica titolo ${file.fileName}",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Elimina ${file.fileName}",
                            tint = Color(0xFFE26C6C),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingInfoCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatRecordingTimestamp(epochMs: Long): String {
    return RecordingTimestampFormatter.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    )
}

private fun formatFileSize(sizeBytes: Long): String {
    val kiloBytes = sizeBytes / 1024.0
    return when {
        kiloBytes < 1024.0 -> "${FileSizeFormatter.format(kiloBytes)} KB"
        else -> "${FileSizeFormatter.format(kiloBytes / 1024.0)} MB"
    }
}

private fun formatDuration(durationMs: Long?): String {
    val safeDuration = durationMs ?: return "-"
    val totalSeconds = safeDuration / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatTrip(value: Double?): String = value?.let { String.format("%.2f km", it) } ?: "-"

private fun formatAngle(value: Double?): String = value?.let { String.format("%.1f deg", it) } ?: "-"

private fun formatAccelDec(accel: Double?, decel: Double?): String {
    val accelText = accel?.let { String.format("%.2f", it) } ?: "-"
    val decelText = decel?.let { String.format("%.2f", it) } ?: "-"
    return "$accelText / $decelText g"
}

private val RecordingTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

private val FileSizeFormatter = DecimalFormat("0.0")
