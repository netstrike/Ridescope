package com.example.ridescope.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.scale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ridescope.data.model.FirmwareConfig
import com.example.ridescope.data.model.FirmwareUpdateAvailability
import com.example.ridescope.data.model.FirmwareUpdateCheckState
import com.example.ridescope.data.model.FirmwareUpdateSettings
import com.example.ridescope.data.model.LongitudinalAccelUnit
import com.example.ridescope.data.model.SensorAxisToken
import com.example.ridescope.data.model.TelemetryGaugeConfig
import com.example.ridescope.data.repository.AppSettingsRepository
import com.example.ridescope.data.repository.FirmwareUpdateRepository
import com.example.ridescope.data.repository.TelemetryRepository
import com.example.ridescope.ui.common.RideScopeCardTitle
import com.example.ridescope.ui.common.RideScopePageTitle
import com.example.ridescope.ui.common.RideScopePopupHost
import com.example.ridescope.ui.common.RideScopePopupType
import com.example.ridescope.ui.common.showRideScopePopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TelemetryPeriodOptions = listOf(50, 100, 150, 200, 250, 300)
private val AxisTokenOptions = SensorAxisToken.entries
private val RollGaugeLimitOptions = TelemetryGaugeConfig.RollLimitOptions
private val PitchGaugeMaxOptions = TelemetryGaugeConfig.PitchMaxOptions
private val LongitudinalGaugeLimitOptions = TelemetryGaugeConfig.LongitudinalAccelLimitOptions
private val SpeedGaugeMaxOptions = TelemetryGaugeConfig.SpeedMaxOptions
private val FirmwareUpdateCheckIntervalOptions = FirmwareUpdateSettings.CheckIntervalOptions
private const val GravityMs2 = 9.80665
private val UpdateOkChipColor = Color(0xFF2E7D32)
private val UpdateNewChipColor = Color(0xFFFFE082)

private enum class SettingsTab(val title: String) {
    Firmware("Firmware"),
    App("Apllicazione"),
}

@Composable
fun SettingsScreen(
    appSettingsRepository: AppSettingsRepository,
    repository: TelemetryRepository,
    paddingValues: PaddingValues,
    pageMenu: (@Composable () -> Unit)? = null,
    pageHomeButton: (@Composable () -> Unit)? = null,
) {
    var config by remember { mutableStateOf(FirmwareConfig()) }
    var savedConfig by remember { mutableStateOf<FirmwareConfig?>(null) }
    var gaugeConfig by remember(appSettingsRepository) {
        mutableStateOf(appSettingsRepository.currentTelemetryGaugeConfig())
    }
    var savedGaugeConfig by remember(appSettingsRepository) {
        mutableStateOf(appSettingsRepository.currentTelemetryGaugeConfig())
    }
    var keepScreenOnEnabled by remember(appSettingsRepository) {
        mutableStateOf(appSettingsRepository.currentKeepScreenOnEnabled())
    }
    var firmwareUpdateSettings by remember(appSettingsRepository) {
        mutableStateOf(appSettingsRepository.currentFirmwareUpdateSettings())
    }
    var savedFirmwareUpdateSettings by remember(appSettingsRepository) {
        mutableStateOf(appSettingsRepository.currentFirmwareUpdateSettings())
    }
    var telemetryPeriodExpanded by remember { mutableStateOf(false) }
    var rollGaugeLimitExpanded by remember { mutableStateOf(false) }
    var pitchGaugeMaxExpanded by remember { mutableStateOf(false) }
    var longitudinalGaugeLimitExpanded by remember { mutableStateOf(false) }
    var speedGaugeMaxExpanded by remember { mutableStateOf(false) }
    var firmwareUpdateIntervalExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(SettingsTab.Firmware) }
    var lateralAxisExpanded by remember { mutableStateOf(false) }
    var longitudinalAxisExpanded by remember { mutableStateOf(false) }
    var verticalAxisExpanded by remember { mutableStateOf(false) }
    val telemetryPeriodValues = remember(config.telemetryPeriodMs) {
        (TelemetryPeriodOptions + config.telemetryPeriodMs).distinct().sorted()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val axisUsageCounts = remember(
        config.bodyLateralAxis,
        config.bodyLongitudinalAxis,
        config.bodyVerticalAxis,
    ) {
        listOf(
            config.bodyLateralAxis,
            config.bodyLongitudinalAxis,
            config.bodyVerticalAxis,
        ).groupingBy(SensorAxisToken::physicalAxis).eachCount()
    }
    val lateralAxisInvalid = (axisUsageCounts[config.bodyLateralAxis.physicalAxis] ?: 0) > 1
    val longitudinalAxisInvalid = (axisUsageCounts[config.bodyLongitudinalAxis.physicalAxis] ?: 0) > 1
    val verticalAxisInvalid = (axisUsageCounts[config.bodyVerticalAxis.physicalAxis] ?: 0) > 1
    val longitudinalGaugeLimitDisplayOptions = remember(gaugeConfig.longitudinalAccelUnit) {
        LongitudinalGaugeLimitOptions.map { option ->
            formatLongitudinalGaugeLimit(option, gaugeConfig.longitudinalAccelUnit)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { repository.readConfig() }
            .onSuccess {
                config = it
                savedConfig = it
            }
            .onFailure {
                snackbarHostState.showRideScopePopup(
                    message = "Errore lettura config: ${it.message}",
                    type = RideScopePopupType.Error,
                )
            }
    }

    LaunchedEffect(config, savedConfig, snackbarHostState) {
        val lastSavedConfig = savedConfig ?: return@LaunchedEffect
        if (config == lastSavedConfig) {
            return@LaunchedEffect
        }
        if (!config.hasValidAxisMap()) {
            return@LaunchedEffect
        }

        delay(250)
        runCatching { repository.writeConfig(config, lastSavedConfig) }
            .onSuccess { updatedConfig ->
                config = updatedConfig
                savedConfig = updatedConfig
            }
            .onFailure {
                snackbarHostState.showRideScopePopup(
                    message = "Errore salvataggio: ${it.message}",
                    type = RideScopePopupType.Error,
                )
            }
    }

    LaunchedEffect(gaugeConfig, savedGaugeConfig) {
        if (gaugeConfig == savedGaugeConfig) {
            return@LaunchedEffect
        }

        delay(250)
        val normalizedConfig = gaugeConfig.normalized()
        appSettingsRepository.updateTelemetryGaugeConfig(normalizedConfig)
        gaugeConfig = normalizedConfig
        savedGaugeConfig = normalizedConfig
    }

    LaunchedEffect(firmwareUpdateSettings, savedFirmwareUpdateSettings) {
        if (firmwareUpdateSettings == savedFirmwareUpdateSettings) {
            return@LaunchedEffect
        }

        delay(250)
        val normalizedSettings = firmwareUpdateSettings.normalized()
        appSettingsRepository.updateFirmwareUpdateSettings(normalizedSettings)
        firmwareUpdateSettings = normalizedSettings
        savedFirmwareUpdateSettings = normalizedSettings
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
                title = "Configurazione",
                menuButton = pageMenu,
                trailingAction = pageHomeButton,
            )
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.title, color = Color.White) },
                    )
                }
            }

            when (selectedTab) {
                SettingsTab.Firmware -> {
                    SectionCard(title = "Telemetria firmware") {
                        ConfigDropdownField(
                            value = config.telemetryPeriodMs.toString(),
                            label = "telemetry_period_ms",
                            options = telemetryPeriodValues.map(Int::toString),
                            expanded = telemetryPeriodExpanded,
                            onExpandedChange = { telemetryPeriodExpanded = it },
                        ) { option ->
                            config = config.copy(telemetryPeriodMs = option.toInt())
                        }

                        RowSwitch(title = "debug_serial", checked = config.debugSerial) {
                            config = config.copy(debugSerial = it)
                        }
                    }

                    SectionCard(title = "Mappa assi sensore") {
                        Text(
                            "Rimappa gli assi fisici del breakout nel frame veicolo del firmware: X laterale, Y longitudinale, Z verticale.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "La tripletta deve usare x, y e z una sola volta. Il salvataggio automatico resta sospeso finche la mappa non torna valida.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (config.hasValidAxisMap()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )

                        ConfigDropdownField(
                            value = config.bodyLateralAxis.wireValue,
                            label = "body_lateral_axis",
                            options = AxisTokenOptions.map(SensorAxisToken::wireValue),
                            expanded = lateralAxisExpanded,
                            isError = lateralAxisInvalid,
                            onExpandedChange = { lateralAxisExpanded = it },
                        ) { option ->
                            config = config.copy(bodyLateralAxis = SensorAxisToken.fromWireValue(option))
                        }
                        ConfigDropdownField(
                            value = config.bodyLongitudinalAxis.wireValue,
                            label = "body_longitudinal_axis",
                            options = AxisTokenOptions.map(SensorAxisToken::wireValue),
                            expanded = longitudinalAxisExpanded,
                            isError = longitudinalAxisInvalid,
                            onExpandedChange = { longitudinalAxisExpanded = it },
                        ) { option ->
                            config = config.copy(bodyLongitudinalAxis = SensorAxisToken.fromWireValue(option))
                        }
                        ConfigDropdownField(
                            value = config.bodyVerticalAxis.wireValue,
                            label = "body_vertical_axis",
                            options = AxisTokenOptions.map(SensorAxisToken::wireValue),
                            expanded = verticalAxisExpanded,
                            isError = verticalAxisInvalid,
                            onExpandedChange = { verticalAxisExpanded = it },
                        ) { option ->
                            config = config.copy(bodyVerticalAxis = SensorAxisToken.fromWireValue(option))
                        }
                    }
                }

                SettingsTab.App -> {
                    SectionCard(title = "Gauge telemetria app") {
                        Text(
                            "Questi limiti regolano solo la scala grafica dell'app. Non vengono inviati al firmware.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ConfigDropdownField(
                            value = gaugeConfig.rollLimitDeg.toString(),
                            label = "roll_gauge_limit_deg",
                            options = RollGaugeLimitOptions.map(Int::toString),
                            expanded = rollGaugeLimitExpanded,
                            onExpandedChange = { rollGaugeLimitExpanded = it },
                        ) { option ->
                            gaugeConfig = gaugeConfig.copy(rollLimitDeg = option.toInt())
                        }
                        ConfigDropdownField(
                            value = gaugeConfig.pitchMaxDeg.toString(),
                            label = "pitch_gauge_max_deg",
                            options = PitchGaugeMaxOptions.map(Int::toString),
                            expanded = pitchGaugeMaxExpanded,
                            onExpandedChange = { pitchGaugeMaxExpanded = it },
                        ) { option ->
                            gaugeConfig = gaugeConfig.copy(pitchMaxDeg = option.toInt())
                        }
                        ConfigDropdownField(
                            value = formatLongitudinalGaugeLimit(
                                gaugeConfig.longitudinalAccelLimitG,
                                gaugeConfig.longitudinalAccelUnit,
                            ),
                            label = "longitudinal_gauge_limit",
                            options = longitudinalGaugeLimitDisplayOptions,
                            expanded = longitudinalGaugeLimitExpanded,
                            onExpandedChange = { longitudinalGaugeLimitExpanded = it },
                        ) { option ->
                            val selectedIndex = longitudinalGaugeLimitDisplayOptions.indexOf(option)
                            if (selectedIndex >= 0) {
                                gaugeConfig = gaugeConfig.copy(
                                    longitudinalAccelLimitG = LongitudinalGaugeLimitOptions[selectedIndex],
                                )
                            }
                        }
                        RowCheckbox(
                            title = "AY in m*s^2",
                            checked = gaugeConfig.longitudinalAccelUnit == LongitudinalAccelUnit.Ms2,
                        ) { enabled ->
                            gaugeConfig = gaugeConfig.copy(
                                longitudinalAccelUnit = if (enabled) LongitudinalAccelUnit.Ms2 else LongitudinalAccelUnit.G,
                            )
                        }
                        ConfigDropdownField(
                            value = gaugeConfig.speedMaxKmh.toString(),
                            label = "speed_gauge_max_kmh",
                            options = SpeedGaugeMaxOptions.map(Int::toString),
                            expanded = speedGaugeMaxExpanded,
                            onExpandedChange = { speedGaugeMaxExpanded = it },
                        ) { option ->
                            gaugeConfig = gaugeConfig.copy(speedMaxKmh = option.toInt())
                        }
                    }

                    SectionCard(title = "Comportamento app") {
                        RowSwitch(
                            title = "mantieni_schermo_attivo",
                            checked = keepScreenOnEnabled,
                        ) { enabled ->
                            keepScreenOnEnabled = enabled
                            appSettingsRepository.updateKeepScreenOnEnabled(enabled)
                        }
                    }

                    FirmwareUpdateSettingsSection(
                        firmwareUpdateSettings = firmwareUpdateSettings,
                        onFirmwareUpdateSettingsChange = { firmwareUpdateSettings = it },
                        firmwareUpdateIntervalExpanded = firmwareUpdateIntervalExpanded,
                        onFirmwareUpdateIntervalExpandedChange = { firmwareUpdateIntervalExpanded = it },
                    )
                }
            }

        }

        RideScopePopupHost(hostState = snackbarHostState)
    }
}

@Composable
fun UpdateScreen(
    appSettingsRepository: AppSettingsRepository,
    firmwareUpdateRepository: FirmwareUpdateRepository,
    paddingValues: PaddingValues,
    pageMenu: (@Composable () -> Unit)? = null,
    pageHomeButton: (@Composable () -> Unit)? = null,
) {
    var firmwareUpdateCheckInProgress by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val firmwareUpdateCheckState by appSettingsRepository.firmwareUpdateCheckState.collectAsState()
    val firmwareUpdateBusy = firmwareUpdateCheckInProgress ||
        firmwareUpdateCheckState.updateInProgress ||
        firmwareUpdateCheckState.appUpdateInProgress

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
                title = "Aggiornamento",
                menuButton = pageMenu,
                trailingAction = pageHomeButton,
            )
            FirmwareUpdateActionsSection(
                firmwareUpdateCheckInProgress = firmwareUpdateCheckInProgress,
                onFirmwareUpdateCheckInProgressChange = { firmwareUpdateCheckInProgress = it },
                firmwareUpdateBusy = firmwareUpdateBusy,
                firmwareUpdateCheckState = firmwareUpdateCheckState,
                firmwareUpdateRepository = firmwareUpdateRepository,
                snackbarHostState = snackbarHostState,
                scope = scope,
            )
        }

        RideScopePopupHost(hostState = snackbarHostState)
    }

    FirmwareUpdateProgressDialog(firmwareUpdateCheckState = firmwareUpdateCheckState)
}

@Composable
private fun FirmwareUpdateSettingsSection(
    firmwareUpdateSettings: FirmwareUpdateSettings,
    onFirmwareUpdateSettingsChange: (FirmwareUpdateSettings) -> Unit,
    firmwareUpdateIntervalExpanded: Boolean,
    onFirmwareUpdateIntervalExpandedChange: (Boolean) -> Unit,
) {
    SectionCard(title = "Parametri aggiornamento") {
        Text(
            "Configura i parametri del repository remoto usati dal controllo aggiornamenti firmware e applicazione.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ConfigTextField(
            value = firmwareUpdateSettings.httpBaseUrl,
            label = "http_base_url",
            keyboardType = KeyboardType.Uri,
        ) { value ->
            onFirmwareUpdateSettingsChange(firmwareUpdateSettings.copy(httpBaseUrl = value))
        }
        ConfigTextField(
            value = firmwareUpdateSettings.firmwareDirectory,
            label = "firmware_http_directory",
        ) { value ->
            onFirmwareUpdateSettingsChange(firmwareUpdateSettings.copy(firmwareDirectory = value))
        }
        ConfigTextField(
            value = firmwareUpdateSettings.appDirectory,
            label = "app_http_directory",
        ) { value ->
            onFirmwareUpdateSettingsChange(firmwareUpdateSettings.copy(appDirectory = value))
        }
        ConfigDropdownField(
            value = formatFirmwareUpdateIntervalMinutes(firmwareUpdateSettings.checkIntervalMinutes),
            label = "check_interval",
            options = FirmwareUpdateCheckIntervalOptions.map(::formatFirmwareUpdateIntervalMinutes),
            expanded = firmwareUpdateIntervalExpanded,
            onExpandedChange = onFirmwareUpdateIntervalExpandedChange,
        ) { option ->
            val selectedIndex = FirmwareUpdateCheckIntervalOptions.indexOfFirst {
                formatFirmwareUpdateIntervalMinutes(it) == option
            }
            if (selectedIndex >= 0) {
                onFirmwareUpdateSettingsChange(
                    firmwareUpdateSettings.copy(
                        checkIntervalMinutes = FirmwareUpdateCheckIntervalOptions[selectedIndex],
                    ),
                )
            }
        }
    }
}

@Composable
private fun FirmwareUpdateActionsSection(
    firmwareUpdateCheckInProgress: Boolean,
    onFirmwareUpdateCheckInProgressChange: (Boolean) -> Unit,
    firmwareUpdateBusy: Boolean,
    firmwareUpdateCheckState: FirmwareUpdateCheckState,
    firmwareUpdateRepository: FirmwareUpdateRepository,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    SectionCard(title = "") {
        val updateFirmwareAction = {
            scope.launch {
                val result = firmwareUpdateRepository.updateFirmware()
                val popupType = when (result.availability) {
                    FirmwareUpdateAvailability.Error -> RideScopePopupType.Error
                    FirmwareUpdateAvailability.UpdateAvailable -> RideScopePopupType.Warning
                    FirmwareUpdateAvailability.UpToDate,
                    FirmwareUpdateAvailability.Unknown,
                    -> null
                }
                if (popupType != null) {
                    snackbarHostState.showRideScopePopup(
                        message = result.statusMessage,
                        type = popupType,
                    )
                }
            }
            Unit
        }
        val updateApplicationAction = {
            scope.launch {
                val result = firmwareUpdateRepository.updateApplication()
                val popupType = when (result.appAvailability) {
                    FirmwareUpdateAvailability.Error -> RideScopePopupType.Error
                    FirmwareUpdateAvailability.UpdateAvailable -> RideScopePopupType.Warning
                    FirmwareUpdateAvailability.UpToDate,
                    FirmwareUpdateAvailability.Unknown,
                    -> null
                }
                if (popupType != null) {
                    snackbarHostState.showRideScopePopup(
                        message = result.appStatusMessage,
                        type = popupType,
                    )
                }
            }
            Unit
        }
        Button(
            onClick = {
                scope.launch {
                    onFirmwareUpdateCheckInProgressChange(true)
                    val result = firmwareUpdateRepository.checkForUpdate(force = true)
                    onFirmwareUpdateCheckInProgressChange(false)
                    if (result != null) {
                        val popupType = popupTypeForUpdateCheck(
                            firmwareAvailability = result.availability,
                            appAvailability = result.appAvailability,
                        )
                        if (popupType != null) {
                            snackbarHostState.showRideScopePopup(
                                message = result.statusMessage,
                                type = popupType,
                            )
                        }
                    }
                }
            },
            enabled = !firmwareUpdateBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (firmwareUpdateCheckInProgress) "CONTROLLO IN CORSO..." else "CONTROLLA ORA")
        }
        Text(
            text = "Ultimo controllo: ${formatFirmwareUpdateLastCheck(firmwareUpdateCheckState.lastCheckedAtEpochMs)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FirmwareUpdateMetadataCard(
                title = "Firmware",
                currentBuild = firmwareUpdateCheckState.currentBuild,
                currentProtocol = firmwareUpdateCheckState.currentProtocol,
                remoteBuild = firmwareUpdateCheckState.remoteBuild,
                remoteProtocol = firmwareUpdateCheckState.remoteProtocol,
                hasUpdate = firmwareUpdateCheckState.availability == FirmwareUpdateAvailability.UpdateAvailable,
                updateActionEnabled = !firmwareUpdateBusy,
                onUpdateClick = updateFirmwareAction.takeIf {
                    firmwareUpdateCheckState.availability == FirmwareUpdateAvailability.UpdateAvailable
                },
                modifier = Modifier.weight(1f),
            )
            FirmwareUpdateMetadataCard(
                title = "App",
                currentBuild = firmwareUpdateCheckState.appCurrentBuild,
                currentProtocol = firmwareUpdateCheckState.appCurrentProtocol,
                remoteBuild = firmwareUpdateCheckState.appRemoteBuild,
                remoteProtocol = firmwareUpdateCheckState.appRemoteProtocol,
                hasUpdate = firmwareUpdateCheckState.appAvailability == FirmwareUpdateAvailability.UpdateAvailable,
                updateActionEnabled = !firmwareUpdateBusy,
                onUpdateClick = updateApplicationAction.takeIf {
                    firmwareUpdateCheckState.appAvailability == FirmwareUpdateAvailability.UpdateAvailable
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FirmwareUpdateProgressDialog(
    firmwareUpdateCheckState: FirmwareUpdateCheckState,
) {
    val firmwareInProgress = firmwareUpdateCheckState.updateInProgress
    val appInProgress = firmwareUpdateCheckState.appUpdateInProgress
    if (!firmwareInProgress && !appInProgress) {
        return
    }

    val title = if (firmwareInProgress) "Aggiornamento firmware" else "Aggiornamento applicazione"
    val statusMessage = if (firmwareInProgress) {
        firmwareUpdateCheckState.statusMessage
    } else {
        firmwareUpdateCheckState.appStatusMessage
    }
    val progressFraction = if (firmwareInProgress) {
        firmwareUpdateCheckState.updateProgressPercent / 100f
    } else {
        firmwareUpdateCheckState.appUpdateProgressPercent / 100f
    }

    AlertDialog(
        onDismissRequest = {},
        containerColor = Color.White,
        titleContentColor = Color.Black,
        textContentColor = Color.Black,
        title = { Text(title, color = Color.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = statusMessage.ifBlank { "Operazione in corso..." },
                    color = Color.Black,
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun FirmwareUpdateMetadataCard(
    title: String,
    currentBuild: String,
    currentProtocol: String,
    remoteBuild: String,
    remoteProtocol: String,
    hasUpdate: Boolean,
    updateActionEnabled: Boolean,
    onUpdateClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RideScopeCardTitle(title = title)
                if (onUpdateClick != null) {
                    FirmwareUpdateActionButton(
                        enabled = updateActionEnabled,
                        onClick = onUpdateClick,
                    )
                } else {
                    FirmwareUpdateAvailabilityChip()
                }
            }
            FirmwareUpdateMetadataLine(
                label = "Build",
                currentValue = currentBuild,
                remoteValue = remoteBuild,
                showRemoteValue = hasUpdate,
            )
            FirmwareUpdateMetadataLine(
                label = "Protocollo",
                currentValue = currentProtocol,
                remoteValue = remoteProtocol,
                showRemoteValue = hasUpdate,
            )
        }
    }
}

@Composable
private fun FirmwareUpdateMetadataLine(
    label: String,
    currentValue: String,
    remoteValue: String,
    showRemoteValue: Boolean,
) {
    val normalizedCurrentValue = currentValue.ifBlank { "-" }
    val normalizedRemoteValue = remoteValue.takeIf { showRemoteValue && it.isNotBlank() && it != currentValue }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = normalizedCurrentValue,
            style = MaterialTheme.typography.bodySmall,
        )
        if (normalizedRemoteValue != null) {
            Text(
                text = "→",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = normalizedRemoteValue,
                style = MaterialTheme.typography.bodySmall,
                color = UpdateNewChipColor,
            )
        }
    }
}

@Composable
private fun FirmwareUpdateAvailabilityChip() {
    Surface(
        color = UpdateOkChipColor,
        contentColor = Color.White,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = "OK",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FirmwareUpdateActionButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(28.dp),
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = UpdateNewChipColor,
            contentColor = Color.Black,
        ),
    ) {
        Text(
            text = "UPD",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title.isNotBlank()) {
                RideScopeCardTitle(title = title)
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigDropdownField(
    value: String,
    label: String,
    options: List<String>,
    expanded: Boolean,
    isError: Boolean = false,
    onExpandedChange: (Boolean) -> Unit,
    onOptionSelected: (String) -> Unit,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
    ) {
        ConfigFieldShell(
            label = label,
            isError = isError,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = Color.Black,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = Color.White) },
                    colors = MenuDefaults.itemColors(textColor = Color.White),
                    onClick = {
                        onOptionSelected(option)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfigTextField(
    value: String,
    label: String,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    ConfigFieldShell(
        label = label,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            visualTransformation = if (password) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConfigFieldShell(
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val borderShape = RoundedCornerShape(16.dp)
    val borderColor = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.outline
    }
    val fieldBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
    val labelBackgroundColor = MaterialTheme.colorScheme.surface

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(1f)
                    .offset(x = 16.dp, y = (-8).dp)
                    .background(color = labelBackgroundColor, shape = RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .background(color = fieldBackgroundColor, shape = borderShape)
                    .border(width = 1.dp, color = borderColor, shape = borderShape)
                    .padding(start = 20.dp, end = 12.dp, top = 0.dp, bottom = 0.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun RowSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.84f),
        )
    }
}

@Composable
private fun RowCheckbox(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun formatLongitudinalGaugeLimit(valueG: Double, unit: LongitudinalAccelUnit): String {
    val displayValue = when (unit) {
        LongitudinalAccelUnit.G -> valueG
        LongitudinalAccelUnit.Ms2 -> valueG * GravityMs2
    }
    val suffix = when (unit) {
        LongitudinalAccelUnit.G -> " g"
        LongitudinalAccelUnit.Ms2 -> " m*s^2"
    }
    return String.format(Locale.US, "%.1f", displayValue) + suffix
}

private fun formatFirmwareUpdateIntervalMinutes(minutes: Int): String {
    return when {
        minutes <= 0 -> "Disattiva"
        minutes == 1 -> "1 min"
        minutes < 60 -> "$minutes min"
        minutes % 1440 == 0 -> "${minutes / 1440} d"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "$minutes min"
    }
}

private fun formatFirmwareUpdateLastCheck(epochMs: Long?): String {
    if (epochMs == null) {
        return "mai"
    }
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return formatter.format(Date(epochMs))
}

private fun popupTypeForUpdateCheck(
    firmwareAvailability: FirmwareUpdateAvailability,
    appAvailability: FirmwareUpdateAvailability,
): RideScopePopupType? {
    return when {
        firmwareAvailability == FirmwareUpdateAvailability.Error ||
            appAvailability == FirmwareUpdateAvailability.Error -> RideScopePopupType.Error
        firmwareAvailability == FirmwareUpdateAvailability.UpdateAvailable ||
            appAvailability == FirmwareUpdateAvailability.UpdateAvailable -> RideScopePopupType.Warning
        else -> null
    }
}
