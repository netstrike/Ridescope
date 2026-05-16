package com.example.ridescope.data.export

import com.garmin.fit.Decoder
import com.garmin.fit.FitListener
import com.garmin.fit.MesgListener
import com.garmin.fit.Sport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class FitRecordingExporterTest {
    @Test
    fun exportGeneratesReadableMotorcyclingActivity() {
        val targetFile = Files.createTempFile("ridescope-fit-", ".fit").toFile()

        try {
            FitRecordingExporter.export(
                targetFile = targetFile,
                recording = FitRecordingExport(
                    title = "Giro collinare",
                    samples = listOf(
                        FitRecordingSample(
                            timestampEpochMs = 1_742_665_600_000,
                            recordingElapsedMs = 0,
                            travelElapsedMs = 0,
                            tripKm = 0.0,
                            latitudeDeg = 45.4642,
                            longitudeDeg = 9.1900,
                            speedKmh = 0.0,
                            altitudeMeters = 118.0,
                        ),
                        FitRecordingSample(
                            timestampEpochMs = 1_742_665_630_000,
                            recordingElapsedMs = 30_000,
                            travelElapsedMs = 30_000,
                            tripKm = 0.45,
                            latitudeDeg = 45.4650,
                            longitudeDeg = 9.1920,
                            speedKmh = 54.0,
                            altitudeMeters = 121.0,
                        ),
                        FitRecordingSample(
                            timestampEpochMs = 1_742_665_660_000,
                            recordingElapsedMs = 60_000,
                            travelElapsedMs = 60_000,
                            tripKm = 1.0,
                            latitudeDeg = 45.4661,
                            longitudeDeg = 9.1952,
                            speedKmh = 66.0,
                            altitudeMeters = 120.0,
                        ),
                    ),
                ),
                appVersionCode = 10010,
                appVersionName = "1.0.10",
            )

            val decoder = Decoder(targetFile.readBytes())
            val listener = FitListener()
            decoder.addListener(listener as MesgListener)
            decoder.read()

            val messages = listener.fitMessages
            assertEquals(1, messages.fileIdMesgs.size)
            assertEquals(1, messages.fileCreatorMesgs.size)
            assertEquals(1, messages.deviceInfoMesgs.size)
            assertEquals(3, messages.recordMesgs.size)
            assertEquals(1, messages.lapMesgs.size)
            assertEquals(1, messages.sessionMesgs.size)
            assertEquals(1, messages.activityMesgs.size)
            assertEquals(Sport.MOTORCYCLING, messages.sessionMesgs.single().sport)
            assertEquals(1000f, messages.sessionMesgs.single().totalDistance, 0.1f)
        } finally {
            targetFile.delete()
        }
    }
}
