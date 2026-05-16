package com.example.ridescope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ridescope.data.model.FirmwareUpdateAvailability
import com.example.ridescope.data.repository.AppSettingsRepository
import com.example.ridescope.data.repository.DevicePowerRepository
import com.example.ridescope.data.repository.FirmwareUpdateRepository
import com.example.ridescope.data.repository.PhoneSensorRepository
import com.example.ridescope.data.repository.RideRecordingRepository
import com.example.ridescope.data.repository.TelemetryRepository
import com.example.ridescope.ui.calibration.CalibrationScreen
import com.example.ridescope.ui.common.RideScopePageHomeButton
import com.example.ridescope.ui.common.RideScopeMenuAction
import com.example.ridescope.ui.common.RideScopePage
import com.example.ridescope.ui.common.RideScopePageMenuButton
import com.example.ridescope.ui.diagnostics.DiagnosticsScreen
import com.example.ridescope.ui.home.HomeScreen
import com.example.ridescope.ui.home.HomeViewModel
import com.example.ridescope.ui.recordings.RecordingsScreen
import com.example.ridescope.ui.recordings.RecordingsViewModel
import com.example.ridescope.ui.settings.FiltersScreen
import com.example.ridescope.ui.settings.SettingsScreen
import com.example.ridescope.ui.settings.UpdateScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

private val AppRootBottomPadding = 16.dp
private val TelemetryPageTop = Color(0xFF1A1F25)
private val TelemetryPageBottom = Color(0xFF090B0F)

@Composable
fun AppRoot(
    appSettingsRepository: AppSettingsRepository,
    devicePowerRepository: DevicePowerRepository,
    firmwareUpdateRepository: FirmwareUpdateRepository,
    rideRecordingRepository: RideRecordingRepository,
    repository: TelemetryRepository,
    phoneSensorRepository: PhoneSensorRepository,
) {
    var selectedPage by remember { mutableStateOf(RideScopePage.Telemetria) }
    val firmwareUpdateCheckState by appSettingsRepository.firmwareUpdateCheckState.collectAsState()
    val updateAvailable = (
        firmwareUpdateCheckState.availability == FirmwareUpdateAvailability.UpdateAvailable ||
        firmwareUpdateCheckState.appAvailability == FirmwareUpdateAvailability.UpdateAvailable
    )
    val factory = remember(repository, phoneSensorRepository, appSettingsRepository, devicePowerRepository, rideRecordingRepository) {
        viewModelFactory {
            initializer {
                HomeViewModel(
                    repository = repository,
                    phoneSensorRepository = phoneSensorRepository,
                    appSettingsRepository = appSettingsRepository,
                    devicePowerRepository = devicePowerRepository,
                    rideRecordingRepository = rideRecordingRepository,
                )
            }
    }
    }
    val homeViewModel: HomeViewModel = viewModel(factory = factory)
    val recordingsFactory = remember(rideRecordingRepository) {
        viewModelFactory {
            initializer {
                RecordingsViewModel(
                    rideRecordingRepository = rideRecordingRepository,
                )
            }
        }
    }
    val recordingsViewModel: RecordingsViewModel = viewModel(factory = recordingsFactory)
    val pageMenu: @Composable () -> Unit = {
        RideScopePageMenuButton(
            selectedPage = selectedPage,
            onSelectPage = { selectedPage = it },
            updateAvailable = updateAvailable,
            extraActions = emptyList(),
        )
    }
    val pageHomeButton: @Composable () -> Unit = {
        RideScopePageHomeButton(
            onClick = { selectedPage = RideScopePage.Telemetria },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(
                Brush.verticalGradient(listOf(TelemetryPageTop, TelemetryPageBottom))
            )
    ) {
        when (selectedPage) {
            RideScopePage.Telemetria -> HomeScreen(
                viewModel = homeViewModel,
                paddingValues = PaddingValues(bottom = AppRootBottomPadding),
                pageMenu = pageMenu,
            )
            RideScopePage.Registrazioni -> RecordingsScreen(
                viewModel = recordingsViewModel,
                paddingValues = PaddingValues(bottom = AppRootBottomPadding),
                pageMenu = pageMenu,
                pageHomeButton = pageHomeButton,
            )
            RideScopePage.Impostazioni -> SettingsScreen(
                appSettingsRepository = appSettingsRepository,
                repository = repository,
                paddingValues = PaddingValues(bottom = AppRootBottomPadding),
                pageMenu = pageMenu,
                pageHomeButton = pageHomeButton,
            )
            RideScopePage.Filtri -> FiltersScreen(
                repository = repository,
                paddingValues = PaddingValues(bottom = AppRootBottomPadding),
                pageMenu = pageMenu,
                pageHomeButton = pageHomeButton,
            )
            RideScopePage.Calibrazione -> CalibrationScreen(
                repository = repository,
                paddingValues = PaddingValues(bottom = AppRootBottomPadding),
                pageMenu = pageMenu,
                pageHomeButton = pageHomeButton,
            )
            RideScopePage.Aggiornamento -> UpdateScreen(
                appSettingsRepository = appSettingsRepository,
                firmwareUpdateRepository = firmwareUpdateRepository,
                paddingValues = PaddingValues(bottom = AppRootBottomPadding),
                pageMenu = pageMenu,
                pageHomeButton = pageHomeButton,
            )
            RideScopePage.Diagnostica -> DiagnosticsScreen(
                repository = repository,
                viewModel = homeViewModel,
                paddingValues = PaddingValues(bottom = AppRootBottomPadding),
                pageMenu = pageMenu,
                pageHomeButton = pageHomeButton,
            )
        }
    }
}
