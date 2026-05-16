package com.example.ridescope.data.repository

import android.content.Context
import android.os.PowerManager

class DevicePowerRepository(
    context: Context,
) {
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    private val processingWakeLock = powerManager
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WakeLockTag)
        ?.apply { setReferenceCounted(false) }

    fun setRecordingProcessingEnabled(enabled: Boolean) {
        val wakeLock = processingWakeLock ?: return
        when {
            enabled && !wakeLock.isHeld -> wakeLock.acquire()
            !enabled && wakeLock.isHeld -> wakeLock.release()
        }
    }

    fun releaseAll() {
        val wakeLock = processingWakeLock ?: return
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private companion object {
        const val WakeLockTag = "RideScope:RecordingProcessing"
    }
}
