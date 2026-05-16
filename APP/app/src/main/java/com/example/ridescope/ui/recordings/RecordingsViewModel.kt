package com.example.ridescope.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ridescope.data.repository.ExportedRecordingFile
import com.example.ridescope.data.repository.RecordingExportFormat
import com.example.ridescope.data.repository.RideRecordingRepository
import com.example.ridescope.data.repository.SavedRecordingFile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordingsUiState(
    val isLoading: Boolean = true,
    val files: List<SavedRecordingFile> = emptyList(),
)

sealed interface RecordingsUiEvent {
    data class ShareExport(
        val exportedFile: ExportedRecordingFile,
    ) : RecordingsUiEvent

    data class ShowError(
        val message: String,
    ) : RecordingsUiEvent
}

class RecordingsViewModel(
    private val rideRecordingRepository: RideRecordingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingsUiState())
    val uiState: StateFlow<RecordingsUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<RecordingsUiEvent>()
    val events: SharedFlow<RecordingsUiEvent> = _events.asSharedFlow()

    fun refreshRecordings() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            _uiState.value = RecordingsUiState(
                isLoading = false,
                files = rideRecordingRepository.listRecordings(),
            )
        }
    }

    fun deleteRecording(fileName: String) {
        viewModelScope.launch {
            rideRecordingRepository.deleteRecording(fileName)
            _uiState.value = _uiState.value.copy(
                files = _uiState.value.files.filterNot { it.fileName == fileName }
            )
        }
    }

    fun exportRecording(fileName: String, format: RecordingExportFormat) {
        viewModelScope.launch {
            runCatching {
                rideRecordingRepository.exportRecording(fileName, format)
            }.onSuccess { exportedFile ->
                _events.emit(RecordingsUiEvent.ShareExport(exportedFile))
            }.onFailure { error ->
                _events.emit(
                    RecordingsUiEvent.ShowError(
                        error.message ?: "Esportazione registrazione fallita",
                    ),
                )
            }
        }
    }

    fun updateRecordingTitle(fileName: String, title: String) {
        viewModelScope.launch {
            runCatching {
                rideRecordingRepository.updateRecordingTitle(
                    fileName = fileName,
                    title = title.ifBlank { "Nessun titolo" },
                )
            }.onSuccess { updatedFile ->
                _uiState.value = _uiState.value.copy(
                    files = _uiState.value.files.map { file ->
                        if (file.fileName == fileName) updatedFile else file
                    },
                )
            }.onFailure { error ->
                _events.emit(
                    RecordingsUiEvent.ShowError(
                        error.message ?: "Aggiornamento titolo fallito",
                    ),
                )
            }
        }
    }
}
