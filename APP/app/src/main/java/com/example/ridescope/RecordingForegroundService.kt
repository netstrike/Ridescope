package com.example.ridescope

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.ridescope.data.model.RecordingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service attivo durante una sessione di registrazione.
 *
 * Garantisce che Android non killi il processo quando l'app è in background,
 * mantenendo attivi BLE, GPS e scrittura campioni su disco.
 * La notifica persistente informa l'utente che la registrazione è in corso.
 */
class RecordingForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundRecording()
            ACTION_STOP -> stopForegroundRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundRecording() {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun stopForegroundRecording() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Registrazione in corso",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notifica attiva durante una sessione di registrazione RideScope"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Registrazione in corso")
            .setContentText("RideScope sta registrando la sessione")
            .setSmallIcon(R.drawable.ic_app_icon_monochrome)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ridescope_recording"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.ridescope.RECORDING_START"
        const val ACTION_STOP = "com.example.ridescope.RECORDING_STOP"

        // Flow aggiornato da HomeViewModel; osservato da MainActivity per avviare/fermare il service
        private val _recordingStatus = MutableStateFlow(RecordingStatus.Idle)
        val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus.asStateFlow()

        fun notifyRecordingStatus(status: RecordingStatus) {
            _recordingStatus.value = status
        }

        fun startIntent(context: Context) = Intent(context, RecordingForegroundService::class.java)
            .apply { action = ACTION_START }

        fun stopIntent(context: Context) = Intent(context, RecordingForegroundService::class.java)
            .apply { action = ACTION_STOP }
    }
}
