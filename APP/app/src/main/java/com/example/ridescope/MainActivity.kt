package com.example.ridescope

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ridescope.data.model.RecordingStatus
import com.example.ridescope.data.network.FirmwareBleClient
import com.example.ridescope.data.repository.AppSettingsRepository
import com.example.ridescope.data.repository.DevicePowerRepository
import com.example.ridescope.data.repository.FirmwareUpdateRepository
import com.example.ridescope.data.repository.PhoneSensorRepository
import com.example.ridescope.data.repository.RideRecordingRepository
import com.example.ridescope.data.repository.TelemetryRepository
import com.example.ridescope.ui.AppRoot
import com.example.ridescope.ui.theme.RideScopeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var bleClient: FirmwareBleClient
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var devicePowerRepository: DevicePowerRepository
    private lateinit var firmwareUpdateRepository: FirmwareUpdateRepository
    private lateinit var rideRecordingRepository: RideRecordingRepository
    private lateinit var repository: TelemetryRepository

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        attemptBleBootstrap()
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        attemptBleBootstrap()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersiveMode()

        bleClient = FirmwareBleClient(applicationContext)
        appSettingsRepository = AppSettingsRepository(applicationContext)
        devicePowerRepository = DevicePowerRepository(applicationContext)
        rideRecordingRepository = RideRecordingRepository(applicationContext)
        repository = TelemetryRepository(bleClient = bleClient)
        firmwareUpdateRepository = FirmwareUpdateRepository(
            context = applicationContext,
            appSettingsRepository = appSettingsRepository,
            telemetryRepository = repository,
        )
        val phoneSensorRepository = PhoneSensorRepository(applicationContext)
        applyKeepScreenOn(appSettingsRepository.currentKeepScreenOnEnabled())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appSettingsRepository.keepScreenOnEnabled.collect { enabled ->
                    applyKeepScreenOn(enabled)
                }
            }
        }

        lifecycleScope.launch {
            RecordingForegroundService.recordingStatus
                .map { it == RecordingStatus.Recording }
                .distinctUntilChanged()
                .collect { isRecording ->
                    if (isRecording) {
                        val intent = RecordingForegroundService.startIntent(this@MainActivity)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } else {
                        stopService(RecordingForegroundService.stopIntent(this@MainActivity))
                    }
                }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appSettingsRepository.firmwareUpdateSettings.collectLatest { updateSettings ->
                    val normalizedSettings = updateSettings.normalized()
                    if (normalizedSettings.checkIntervalMinutes <= 0) {
                        return@collectLatest
                    }
                    val intervalMs = normalizedSettings.checkIntervalMinutes * 60_000L

                    firmwareUpdateRepository.checkForUpdate(force = true)
                    while (true) {
                        delay(intervalMs)
                        firmwareUpdateRepository.checkForUpdate(force = false)
                    }
                }
            }
        }

        setContent {
            RideScopeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        appSettingsRepository = appSettingsRepository,
                        devicePowerRepository = devicePowerRepository,
                        firmwareUpdateRepository = firmwareUpdateRepository,
                        rideRecordingRepository = rideRecordingRepository,
                        repository = repository,
                        phoneSensorRepository = phoneSensorRepository,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        attemptBleBootstrap()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureImmersiveMode()
        }
    }

    override fun onDestroy() {
        if (::devicePowerRepository.isInitialized) {
            devicePowerRepository.releaseAll()
        }
        if (::repository.isInitialized) {
            repository.shutdownTransport()
        }
        super.onDestroy()
    }

    private fun configureImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun attemptBleBootstrap() {
        if (!::bleClient.isInitialized || !::repository.isInitialized) {
            return
        }

        if (!bleClient.hasRequiredPermissions()) {
            blePermissionLauncher.launch(bleClient.requiredPermissions())
            return
        }

        if (!bleClient.isBluetoothEnabled()) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        repository.ensureTransportStarted()
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
