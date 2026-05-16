package com.example.ridescope.data.export

import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime as FitDateTime
import com.garmin.fit.DeviceIndex
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File as FitFile
import com.garmin.fit.FileCreatorMesg
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

internal data class FitRecordingExport(
    val title: String,
    val samples: List<FitRecordingSample>,
)

internal data class FitRecordingSample(
    val timestampEpochMs: Long,
    val recordingElapsedMs: Long,
    val travelElapsedMs: Long,
    val tripKm: Double,
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    val speedKmh: Double,
    val altitudeMeters: Double?,
)

internal object FitRecordingExporter {
    fun export(
        targetFile: File,
        recording: FitRecordingExport,
        appVersionCode: Int,
        appVersionName: String,
    ) {
        val orderedSamples = recording.samples.sortedBy(FitRecordingSample::timestampEpochMs)
        require(orderedSamples.isNotEmpty()) { "Registrazione senza campioni esportabili in FIT" }

        targetFile.parentFile?.mkdirs()

        val stats = FitExportStats.from(orderedSamples)
        val startTime = FitDateTime(Instant.ofEpochMilli(stats.startEpochMs))
        val endTime = FitDateTime(Instant.ofEpochMilli(stats.endEpochMs))
        val serialNumber = (stats.startEpochMs / 1000L).coerceAtLeast(1L)
        val encoder = FileEncoder(targetFile, Fit.ProtocolVersion.V2_0)

        try {
            encoder.write(createFileIdMesg(startTime, serialNumber))
            encoder.write(createFileCreatorMesg(appVersionCode))
            encoder.write(createDeviceInfoMesg(startTime, serialNumber, appVersionName))
            encoder.write(createTimerEventMesg(startTime, EventType.START))
            orderedSamples.forEach { sample ->
                encoder.write(createRecordMesg(sample))
            }
            encoder.write(createTimerEventMesg(endTime, EventType.STOP))
            encoder.write(createLapMesg(startTime, endTime, stats))
            encoder.write(createSessionMesg(startTime, endTime, stats))
            encoder.write(createActivityMesg(endTime, stats))
        } finally {
            encoder.close()
        }
    }

    private fun createFileIdMesg(
        startTime: FitDateTime,
        serialNumber: Long,
    ): FileIdMesg {
        return FileIdMesg().apply {
            setType(FitFile.ACTIVITY)
            setManufacturer(Manufacturer.DEVELOPMENT)
            setProduct(FitProductIdRideScope)
            setProductName(FitProductName)
            setSerialNumber(serialNumber)
            setTimeCreated(FitDateTime(startTime))
        }
    }

    private fun createFileCreatorMesg(appVersionCode: Int): FileCreatorMesg {
        return FileCreatorMesg().apply {
            setSoftwareVersion(appVersionCode)
        }
    }

    private fun createDeviceInfoMesg(
        startTime: FitDateTime,
        serialNumber: Long,
        appVersionName: String,
    ): DeviceInfoMesg {
        return DeviceInfoMesg().apply {
            setDeviceIndex(DeviceIndex.CREATOR)
            setManufacturer(Manufacturer.DEVELOPMENT)
            setProduct(FitProductIdRideScope)
            setProductName(FitProductName)
            setSerialNumber(serialNumber)
            setSoftwareVersion(appVersionName.toFitSoftwareVersion())
            setTimestamp(FitDateTime(startTime))
        }
    }

    private fun createTimerEventMesg(
        timestamp: FitDateTime,
        eventType: EventType,
    ): EventMesg {
        return EventMesg().apply {
            setTimestamp(FitDateTime(timestamp))
            setEvent(Event.TIMER)
            setEventType(eventType)
            setEventGroup(DefaultEventGroup)
        }
    }

    private fun createRecordMesg(sample: FitRecordingSample): RecordMesg {
        val speedMps = (sample.speedKmh / SecondsPerHour).toFloat().coerceAtLeast(0f)
        val distanceM = (sample.tripKm * MetersPerKilometer).toFloat().coerceAtLeast(0f)
        return RecordMesg().apply {
            setTimestamp(FitDateTime(Instant.ofEpochMilli(sample.timestampEpochMs)))
            sample.latitudeDeg?.let { latitude ->
                sample.longitudeDeg?.let { longitude ->
                    setPositionLat(latitude.toLatitudeSemicircles())
                    setPositionLong(longitude.toLongitudeSemicircles())
                }
            }
            sample.altitudeMeters?.toFloat()?.let { altitude ->
                setAltitude(altitude)
                setEnhancedAltitude(altitude)
            }
            setDistance(distanceM)
            setSpeed(speedMps)
            setEnhancedSpeed(speedMps)
        }
    }

    private fun createLapMesg(
        startTime: FitDateTime,
        endTime: FitDateTime,
        stats: FitExportStats,
    ): LapMesg {
        return LapMesg().apply {
            setMessageIndex(0)
            setEvent(Event.LAP)
            setEventType(EventType.STOP)
            setEventGroup(DefaultEventGroup)
            setLapTrigger(LapTrigger.SESSION_END)
            setSport(FitSport)
            setSubSport(FitSubSport)
            setStartTime(FitDateTime(startTime))
            setTimestamp(FitDateTime(endTime))
            setTotalElapsedTime(stats.totalElapsedTimeSec)
            setTotalTimerTime(stats.totalTimerTimeSec)
            setTotalDistance(stats.totalDistanceM)
            if (stats.avgSpeedMps > 0f) {
                setAvgSpeed(stats.avgSpeedMps)
            }
            if (stats.maxSpeedMps > 0f) {
                setMaxSpeed(stats.maxSpeedMps)
            }
            stats.startPosition?.let { position ->
                setStartPositionLat(position.latitudeSemicircles)
                setStartPositionLong(position.longitudeSemicircles)
            }
            stats.endPosition?.let { position ->
                setEndPositionLat(position.latitudeSemicircles)
                setEndPositionLong(position.longitudeSemicircles)
            }
            applyAltitudeStats(stats.altitudeStats)
        }
    }

    private fun createSessionMesg(
        startTime: FitDateTime,
        endTime: FitDateTime,
        stats: FitExportStats,
    ): SessionMesg {
        return SessionMesg().apply {
            setMessageIndex(0)
            setEvent(Event.SESSION)
            setEventType(EventType.STOP)
            setEventGroup(DefaultEventGroup)
            setStartTime(FitDateTime(startTime))
            setTimestamp(FitDateTime(endTime))
            setStartPositionLat(stats.startPosition?.latitudeSemicircles)
            setStartPositionLong(stats.startPosition?.longitudeSemicircles)
            setEndPositionLat(stats.endPosition?.latitudeSemicircles)
            setEndPositionLong(stats.endPosition?.longitudeSemicircles)
            setSport(FitSport)
            setSubSport(FitSubSport)
            setTotalElapsedTime(stats.totalElapsedTimeSec)
            setTotalTimerTime(stats.totalTimerTimeSec)
            setTotalDistance(stats.totalDistanceM)
            if (stats.avgSpeedMps > 0f) {
                setAvgSpeed(stats.avgSpeedMps)
            }
            if (stats.maxSpeedMps > 0f) {
                setMaxSpeed(stats.maxSpeedMps)
            }
            setFirstLapIndex(0)
            setNumLaps(1)
            applyAltitudeStats(stats.altitudeStats)
        }
    }

    private fun createActivityMesg(
        endTime: FitDateTime,
        stats: FitExportStats,
    ): ActivityMesg {
        val localTimestamp = endTime.timestamp + stats.endZoneOffsetSeconds
        return ActivityMesg().apply {
            setTimestamp(FitDateTime(endTime))
            setTotalTimerTime(stats.totalTimerTimeSec)
            setNumSessions(1)
            setType(Activity.MANUAL)
            setEvent(Event.ACTIVITY)
            setEventType(EventType.STOP)
            setEventGroup(DefaultEventGroup)
            setLocalTimestamp(localTimestamp)
        }
    }

    private fun LapMesg.applyAltitudeStats(altitudeStats: FitAltitudeStats?) {
        altitudeStats ?: return
        setAvgAltitude(altitudeStats.avgAltitudeM)
        setMaxAltitude(altitudeStats.maxAltitudeM)
        setMinAltitude(altitudeStats.minAltitudeM)
        setTotalAscent(altitudeStats.totalAscentM)
        setTotalDescent(altitudeStats.totalDescentM)
    }

    private fun SessionMesg.applyAltitudeStats(altitudeStats: FitAltitudeStats?) {
        altitudeStats ?: return
        setAvgAltitude(altitudeStats.avgAltitudeM)
        setMaxAltitude(altitudeStats.maxAltitudeM)
        setTotalAscent(altitudeStats.totalAscentM)
        setTotalDescent(altitudeStats.totalDescentM)
    }

    private fun String.toFitSoftwareVersion(): Float {
        val parts = split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return 1f
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major + (minor / 100f) + (patch / 10_000f)
    }

    private fun Double.toLatitudeSemicircles(): Int = toSemicircles(coerceIn(-90.0, 90.0))

    private fun Double.toLongitudeSemicircles(): Int = toSemicircles(coerceIn(-180.0, 180.0))

    private fun toSemicircles(valueDeg: Double): Int {
        val rawValue = valueDeg * SemicirclesPerDegree
        return rawValue
            .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
            .roundToInt()
    }

}

private data class FitExportStats(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val endZoneOffsetSeconds: Long,
    val totalElapsedTimeSec: Float,
    val totalTimerTimeSec: Float,
    val totalDistanceM: Float,
    val avgSpeedMps: Float,
    val maxSpeedMps: Float,
    val startPosition: FitPosition?,
    val endPosition: FitPosition?,
    val altitudeStats: FitAltitudeStats?,
) {
    companion object {
        fun from(samples: List<FitRecordingSample>): FitExportStats {
            require(samples.isNotEmpty()) { "Registrazione senza campioni esportabili in FIT" }

            val firstSample = samples.first()
            val lastSample = samples.last()
            val startEpochMs = firstSample.timestampEpochMs
            val endEpochMs = lastSample.timestampEpochMs
            val measuredElapsedSec = (samples.maxOfOrNull(FitRecordingSample::recordingElapsedMs) ?: 0L) / 1000f
            val measuredTimerSec = (samples.maxOfOrNull(FitRecordingSample::travelElapsedMs) ?: 0L) / 1000f
            val fallbackElapsedSec = ((endEpochMs - startEpochMs).coerceAtLeast(0L)) / 1000f
            val totalElapsedTimeSec = measuredElapsedSec.takeIf { it > 0f } ?: fallbackElapsedSec
            val totalTimerTimeSec = measuredTimerSec.takeIf { it > 0f } ?: totalElapsedTimeSec
            val totalDistanceM = ((samples.maxOfOrNull(FitRecordingSample::tripKm) ?: 0.0) * 1000.0).toFloat()
            val avgSpeedMps = if (totalTimerTimeSec > 0f && totalDistanceM > 0f) {
                totalDistanceM / totalTimerTimeSec
            } else {
                0f
            }
            val maxSpeedMps = ((samples.maxOfOrNull(FitRecordingSample::speedKmh) ?: 0.0) / 3.6).toFloat()
            val startPosition = samples.firstNotNullOfOrNull { sample ->
                sample.latitudeDeg?.let { latitude ->
                    sample.longitudeDeg?.let { longitude ->
                        FitPosition(
                            latitudeSemicircles = latitude.toFitLatitudeSemicircles(),
                            longitudeSemicircles = longitude.toFitLongitudeSemicircles(),
                        )
                    }
                }
            }
            val endPosition = samples.asReversed().firstNotNullOfOrNull { sample ->
                sample.latitudeDeg?.let { latitude ->
                    sample.longitudeDeg?.let { longitude ->
                        FitPosition(
                            latitudeSemicircles = latitude.toFitLatitudeSemicircles(),
                            longitudeSemicircles = longitude.toFitLongitudeSemicircles(),
                        )
                    }
                }
            }
            val endZoneOffsetSeconds = Instant.ofEpochMilli(endEpochMs)
                .atZone(ZoneId.systemDefault())
                .offset
                .totalSeconds
                .toLong()

            return FitExportStats(
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs,
                endZoneOffsetSeconds = endZoneOffsetSeconds,
                totalElapsedTimeSec = totalElapsedTimeSec,
                totalTimerTimeSec = totalTimerTimeSec,
                totalDistanceM = totalDistanceM,
                avgSpeedMps = avgSpeedMps,
                maxSpeedMps = maxSpeedMps,
                startPosition = startPosition,
                endPosition = endPosition,
                altitudeStats = FitAltitudeStats.from(samples),
            )
        }
    }
}

private const val FitProductIdRideScope = 1
private const val FitProductName = "RideScope"
private const val SecondsPerHour = 3.6
private const val MetersPerKilometer = 1000.0
private const val SemicirclesPerDegree = 2147483648.0 / 180.0
private val FitSport: Sport = Sport.MOTORCYCLING
private val FitSubSport: SubSport = SubSport.GENERIC
private const val DefaultEventGroup: Short = 0

private fun Double.toFitLatitudeSemicircles(): Int = toFitSemicircles(coerceIn(-90.0, 90.0))

private fun Double.toFitLongitudeSemicircles(): Int = toFitSemicircles(coerceIn(-180.0, 180.0))

private fun toFitSemicircles(valueDeg: Double): Int {
    val rawValue = valueDeg * SemicirclesPerDegree
    return rawValue
        .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
        .roundToInt()
}

private data class FitPosition(
    val latitudeSemicircles: Int,
    val longitudeSemicircles: Int,
)

private data class FitAltitudeStats(
    val avgAltitudeM: Float,
    val maxAltitudeM: Float,
    val minAltitudeM: Float,
    val totalAscentM: Int,
    val totalDescentM: Int,
) {
    companion object {
        fun from(samples: List<FitRecordingSample>): FitAltitudeStats? {
            val altitudeSamples = samples.mapNotNull(FitRecordingSample::altitudeMeters)
            if (altitudeSamples.isEmpty()) {
                return null
            }

            var totalAscentM = 0.0
            var totalDescentM = 0.0
            var previousAltitudeM: Double? = null

            samples.forEach { sample ->
                val currentAltitudeM = sample.altitudeMeters ?: return@forEach
                previousAltitudeM?.let { previous ->
                    val delta = currentAltitudeM - previous
                    if (delta > 0.0) {
                        totalAscentM += delta
                    } else if (delta < 0.0) {
                        totalDescentM += -delta
                    }
                }
                previousAltitudeM = currentAltitudeM
            }

            return FitAltitudeStats(
                avgAltitudeM = altitudeSamples.average().toFloat(),
                maxAltitudeM = altitudeSamples.maxOrNull()?.toFloat() ?: return null,
                minAltitudeM = altitudeSamples.minOrNull()?.toFloat() ?: return null,
                totalAscentM = totalAscentM.roundToInt(),
                totalDescentM = totalDescentM.roundToInt(),
            )
        }
    }
}
