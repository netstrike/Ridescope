package com.example.ridescope.data.repository

import android.content.Context
import com.example.ridescope.BuildConfig
import com.example.ridescope.data.export.FitRecordingExport
import com.example.ridescope.data.export.FitRecordingExporter
import com.example.ridescope.data.export.FitRecordingSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

data class SavedRecordingFile(
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long,
    val title: String = "",
    val travelDurationMs: Long? = null,
    val tripKm: Double? = null,
    val maxRollDeg: Double? = null,
    val maxPitchDeg: Double? = null,
    val maxAccelG: Double? = null,
    val maxDecelG: Double? = null,
)

enum class RecordingExportFormat {
    Csv,
    Gpx,
    Fit,
}

data class ExportedRecordingFile(
    val file: File,
    val mimeType: String,
)

data class RecordingSample(
    val timestampEpochMs: Long,
    val recordingElapsedMs: Long,
    val travelElapsedMs: Long,
    val tripKm: Double = 0.0,
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val speedKmh: Double = 0.0,
    val altitudeMeters: Double? = null,
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val accelG: Double = 0.0,
    val decelG: Double = 0.0,
)

data class RecordingSummary(
    val title: String,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val totalDurationMs: Long,
    val travelDurationMs: Long,
    val tripKm: Double,
    val maxSpeedKmh: Double,
    val maxAltitudeMeters: Double,
    val maxRollDeg: Double,
    val maxPitchDeg: Double,
    val maxAccelG: Double,
    val maxDecelG: Double,
)

class RideRecordingRepository(
    context: Context,
) {
    private val appContext = context.applicationContext

    class Session internal constructor(
        internal val tempFile: File,
        internal val writer: BufferedWriter,
        val outputFileName: String,
        val startedAtEpochMs: Long,
    ) {
        @Volatile
        internal var closed: Boolean = false
    }

    private val recordingsDir = File(context.applicationContext.filesDir, "recordings").apply { mkdirs() }
    private val ioMutex = Mutex()

    suspend fun startSession(
        outputFileName: String,
        startedAtEpochMs: Long,
    ): Session = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("ride_recording_", ".pending.xml", recordingsDir)
        val writer = tempFile.bufferedWriter()
        writer.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        writer.appendLine(
            """<rideScopeRecording fileName="${escapeXml(outputFileName)}" startedAt="${XmlTimestampFormatter.format(Instant.ofEpochMilli(startedAtEpochMs).atZone(ZoneId.systemDefault()))}">"""
        )
        writer.appendLine("""  <samples>""")
        writer.flush()
        Session(
            tempFile = tempFile,
            writer = writer,
            outputFileName = outputFileName,
            startedAtEpochMs = startedAtEpochMs,
        )
    }

    suspend fun appendSample(session: Session, sample: RecordingSample) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (session.closed) {
                return@withLock
            }
            session.writer.appendLine(sample.toXmlLine())
            session.writer.flush()
        }
    }

    suspend fun finalizeSession(
        session: Session,
        summary: RecordingSummary,
    ): File = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!session.closed) {
                session.writer.appendLine("""  </samples>""")
                session.writer.appendLine("""  <summary>""")
                session.writer.appendLine("""    <title>${escapeXml(summary.title)}</title>""")
                session.writer.appendLine(
                    """    <startDateTime>${XmlTimestampFormatter.format(Instant.ofEpochMilli(summary.startedAtEpochMs).atZone(ZoneId.systemDefault()))}</startDateTime>"""
                )
                session.writer.appendLine(
                    """    <stopDateTime>${XmlTimestampFormatter.format(Instant.ofEpochMilli(summary.stoppedAtEpochMs).atZone(ZoneId.systemDefault()))}</stopDateTime>"""
                )
                session.writer.appendLine("""    <recordingTimeMs>${summary.totalDurationMs}</recordingTimeMs>""")
                session.writer.appendLine("""    <travelTimeMs>${summary.travelDurationMs}</travelTimeMs>""")
                session.writer.appendLine("""    <tripKm>${formatNumber(summary.tripKm)}</tripKm>""")
                session.writer.appendLine("""    <vMaxKmh>${formatNumber(summary.maxSpeedKmh)}</vMaxKmh>""")
                session.writer.appendLine("""    <maxAltitudeM>${formatNumber(summary.maxAltitudeMeters)}</maxAltitudeM>""")
                session.writer.appendLine("""    <maxRollDeg>${formatNumber(summary.maxRollDeg)}</maxRollDeg>""")
                session.writer.appendLine("""    <maxPitchDeg>${formatNumber(summary.maxPitchDeg)}</maxPitchDeg>""")
                session.writer.appendLine("""    <maxAccelG>${formatNumber(summary.maxAccelG)}</maxAccelG>""")
                session.writer.appendLine("""    <maxDecelG>${formatNumber(summary.maxDecelG)}</maxDecelG>""")
                session.writer.appendLine("""  </summary>""")
                session.writer.appendLine("""</rideScopeRecording>""")
                session.writer.flush()
                session.writer.close()
                session.closed = true
            }
        }

        val targetFile = uniqueOutputFile(session.outputFileName.removeSuffix(".xml"))
        if (!session.tempFile.renameTo(targetFile)) {
            error("Impossibile salvare il file XML finale")
        }
        targetFile
    }

    suspend fun discardSession(session: Session) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!session.closed) {
                session.writer.close()
                session.closed = true
            }
        }
        session.tempFile.delete()
    }

    suspend fun listRecordings(): List<SavedRecordingFile> = withContext(Dispatchers.IO) {
        recordingsDir
            .listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.extension.equals("xml", ignoreCase = true) &&
                    !file.name.endsWith(".pending.xml", ignoreCase = true)
            }
            .sortedByDescending(File::lastModified)
            .map { file -> file.toSavedRecordingFile() }
    }

    suspend fun deleteRecording(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(recordingsDir, fileName).canonicalFile
        val canonicalDir = recordingsDir.canonicalFile
        if (targetFile.parentFile != canonicalDir || !targetFile.exists() || !targetFile.isFile) {
            return@withContext false
        }
        targetFile.delete()
    }

    suspend fun updateRecordingTitle(fileName: String, title: String): SavedRecordingFile = withContext(Dispatchers.IO) {
        val targetFile = resolveRecordingFile(fileName)
        val originalLastModified = targetFile.lastModified()
        val xmlDocument = XmlDocBuilderFactory.newDocumentBuilder().parse(targetFile)
        val root = xmlDocument.documentElement
        val summaryElement = root.getElementsByTagName("summary").item(0) as? Element
            ?: error("Summary registrazione non trovata")
        val normalizedTitle = title.ifBlank { "Nessun titolo" }
        val titleElement = (summaryElement.getElementsByTagName("title").item(0) as? Element)
            ?: xmlDocument.createElement("title").also { createdElement ->
                val firstChild = summaryElement.firstChild
                if (firstChild != null) {
                    summaryElement.insertBefore(createdElement, firstChild)
                } else {
                    summaryElement.appendChild(createdElement)
                }
            }
        titleElement.textContent = normalizedTitle

        val transformer = XmlTransformerFactory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        targetFile.outputStream().use { output ->
            transformer.transform(DOMSource(xmlDocument), StreamResult(output))
        }
        if (originalLastModified > 0L) {
            targetFile.setLastModified(originalLastModified)
        }
        targetFile.toSavedRecordingFile()
    }

    suspend fun exportRecording(
        fileName: String,
        format: RecordingExportFormat,
    ): ExportedRecordingFile = withContext(Dispatchers.IO) {
        val sourceFile = resolveRecordingFile(fileName)
        val document = parseRecordingDocument(sourceFile)
        val exportDir = File(appContext.cacheDir, "recording_exports").apply { mkdirs() }
        val targetFile = uniqueExportFile(
            exportDir = exportDir,
            baseName = sourceFile.nameWithoutExtension,
            extension = when (format) {
                RecordingExportFormat.Csv -> "csv"
                RecordingExportFormat.Gpx -> "gpx"
                RecordingExportFormat.Fit -> "fit"
            },
        )
        when (format) {
            RecordingExportFormat.Csv -> targetFile.writeText(document.toCsvString())
            RecordingExportFormat.Gpx -> targetFile.writeText(document.toGpxString())
            RecordingExportFormat.Fit -> document.writeFitFile(
                targetFile = targetFile,
                sourceBaseName = sourceFile.nameWithoutExtension,
            )
        }
        ExportedRecordingFile(
            file = targetFile,
            mimeType = when (format) {
                RecordingExportFormat.Csv -> "text/csv"
                RecordingExportFormat.Gpx -> "application/gpx+xml"
                RecordingExportFormat.Fit -> "application/vnd.ant.fit"
            },
        )
    }

    private fun uniqueOutputFile(baseName: String): File {
        var suffix = 0
        while (true) {
            val candidateName = if (suffix == 0) {
                "$baseName.xml"
            } else {
                "$baseName-$suffix.xml"
            }
            val candidate = File(recordingsDir, candidateName)
            if (!candidate.exists()) {
                return candidate
            }
            suffix++
        }
    }

    private fun uniqueExportFile(
        exportDir: File,
        baseName: String,
        extension: String,
    ): File {
        var suffix = 0
        while (true) {
            val candidateName = if (suffix == 0) {
                "$baseName-export.$extension"
            } else {
                "$baseName-export-$suffix.$extension"
            }
            val candidate = File(exportDir, candidateName)
            if (!candidate.exists()) {
                return candidate
            }
            suffix++
        }
    }

    private fun RecordingSample.toXmlLine(): String {
        val isoTimestamp = XmlTimestampFormatter.format(
            Instant.ofEpochMilli(timestampEpochMs).atZone(ZoneId.systemDefault())
        )
        return buildString {
            append("""    <sample""")
            append(""" timestamp="${escapeXml(isoTimestamp)}"""")
            append(""" recordingElapsedMs="$recordingElapsedMs"""")
            append(""" travelElapsedMs="$travelElapsedMs"""")
            append(""" tripKm="${formatNumber(tripKm)}"""")
            append(""" latitudeDeg="${formatNullable(latitudeDeg)}"""")
            append(""" longitudeDeg="${formatNullable(longitudeDeg)}"""")
            append(""" speedKmh="${formatNumber(speedKmh)}"""")
            append(""" altitudeM="${formatNullable(altitudeMeters)}"""")
            append(""" rollDeg="${formatNumber(rollDeg)}"""")
            append(""" pitchDeg="${formatNumber(pitchDeg)}"""")
            append(""" accelG="${formatNumber(accelG)}"""")
            append(""" decelG="${formatNumber(decelG)}"""")
            append(""" />""")
        }
    }

    private fun formatNumber(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun formatNullable(value: Double?): String {
        return value?.let(::formatNumber).orEmpty()
    }

    private fun resolveRecordingFile(fileName: String): File {
        val targetFile = File(recordingsDir, fileName).canonicalFile
        val canonicalDir = recordingsDir.canonicalFile
        if (targetFile.parentFile != canonicalDir || !targetFile.exists() || !targetFile.isFile) {
            error("File registrazione non trovato")
        }
        return targetFile
    }

    private fun parseRecordingDocument(file: File): ParsedRecordingDocument {
        val xmlDocument = XmlDocBuilderFactory.newDocumentBuilder().parse(file)
        val root = xmlDocument.documentElement
        val summaryElement = root.getElementsByTagName("summary").item(0) as? Element
        val sampleNodes = root.getElementsByTagName("sample")
        val samples = buildList {
            for (index in 0 until sampleNodes.length) {
                val element = sampleNodes.item(index) as? Element ?: continue
                add(
                    ParsedRecordingSample(
                        timestampIso = element.attr("timestamp"),
                        timestampEpochMs = parseRecordingTimestampEpochMs(element.attr("timestamp")),
                        recordingElapsedMs = element.attr("recordingElapsedMs").toLongOrNull() ?: 0L,
                        travelElapsedMs = element.attr("travelElapsedMs").toLongOrNull() ?: 0L,
                        tripKm = element.attr("tripKm").toDoubleOrNull() ?: 0.0,
                        latitudeDeg = element.attr("latitudeDeg").toDoubleOrNull(),
                        longitudeDeg = element.attr("longitudeDeg").toDoubleOrNull(),
                        speedKmh = element.attr("speedKmh").toDoubleOrNull() ?: 0.0,
                        altitudeMeters = element.attr("altitudeM").toDoubleOrNull(),
                        rollDeg = element.attr("rollDeg").toDoubleOrNull() ?: 0.0,
                        pitchDeg = element.attr("pitchDeg").toDoubleOrNull() ?: 0.0,
                        accelG = element.attr("accelG").toDoubleOrNull() ?: 0.0,
                        decelG = element.attr("decelG").toDoubleOrNull() ?: 0.0,
                    )
                )
            }
        }
        return ParsedRecordingDocument(
            title = summaryElement?.childText("title").orEmpty(),
            summary = summaryElement?.let {
                ParsedRecordingSummary(
                    title = it.childText("title").ifBlank { "Nessun titolo" },
                    travelDurationMs = it.childText("travelTimeMs").toLongOrNull(),
                    tripKm = it.childText("tripKm").toDoubleOrNull(),
                    maxRollDeg = it.childText("maxRollDeg").toDoubleOrNull(),
                    maxPitchDeg = it.childText("maxPitchDeg").toDoubleOrNull(),
                    maxAccelG = it.childText("maxAccelG").toDoubleOrNull(),
                    maxDecelG = it.childText("maxDecelG").toDoubleOrNull(),
                )
            },
            samples = samples,
        )
    }

    private fun ParsedRecordingDocument.toCsvString(): String {
        val header =
            "timestamp_iso,recording_elapsed_ms,travel_elapsed_ms,trip_km,latitude_deg,longitude_deg,speed_kmh,altitude_m,roll_deg,pitch_deg,accel_g,decel_g"
        val rows = samples.joinToString(separator = "\n") { sample ->
            listOf(
                sample.timestampIso,
                sample.recordingElapsedMs.toString(),
                sample.travelElapsedMs.toString(),
                formatNumber(sample.tripKm),
                formatNullable(sample.latitudeDeg),
                formatNullable(sample.longitudeDeg),
                formatNumber(sample.speedKmh),
                formatNullable(sample.altitudeMeters),
                formatNumber(sample.rollDeg),
                formatNumber(sample.pitchDeg),
                formatNumber(sample.accelG),
                formatNumber(sample.decelG),
            ).joinToString(",")
        }
        return if (rows.isBlank()) header else "$header\n$rows"
    }

    private fun ParsedRecordingDocument.toGpxString(): String {
        val trackName = escapeXml(summary?.title?.ifBlank { title.ifBlank { "Nessun titolo" } } ?: "Nessun titolo")
        val trackPoints = samples
            .filter { it.latitudeDeg != null && it.longitudeDeg != null }
            .joinToString(separator = "\n") { sample ->
                buildString {
                    append("""      <trkpt lat="${formatNumber(sample.latitudeDeg ?: 0.0)}" lon="${formatNumber(sample.longitudeDeg ?: 0.0)}">""")
                    sample.altitudeMeters?.let { altitude ->
                        append("\n")
                        append("""        <ele>${formatNumber(altitude)}</ele>""")
                    }
                    append("\n")
                    append("""        <time>${sample.timestampIso.replace(" ", "T")}</time>""")
                    append("\n")
                    append("""        <extensions>""")
                    append("\n")
                    append("""          <ridescope:speedKmh>${formatNumber(sample.speedKmh)}</ridescope:speedKmh>""")
                    append("\n")
                    append("""          <ridescope:rollDeg>${formatNumber(sample.rollDeg)}</ridescope:rollDeg>""")
                    append("\n")
                    append("""          <ridescope:pitchDeg>${formatNumber(sample.pitchDeg)}</ridescope:pitchDeg>""")
                    append("\n")
                    append("""          <ridescope:accelG>${formatNumber(sample.accelG)}</ridescope:accelG>""")
                    append("\n")
                    append("""          <ridescope:decelG>${formatNumber(sample.decelG)}</ridescope:decelG>""")
                    append("\n")
                    append("""        </extensions>""")
                    append("\n")
                    append("""      </trkpt>""")
                }
            }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<gpx version="1.1" creator="RideScope" xmlns="http://www.topografix.com/GPX/1/1" xmlns:ridescope="https://ridescope.app/ns/1">
            |  <metadata>
            |    <name>$trackName</name>
            |  </metadata>
            |  <trk>
            |    <name>$trackName</name>
            |    <trkseg>
            |$trackPoints
            |    </trkseg>
            |  </trk>
            |</gpx>
        """.trimMargin()
    }

    private fun ParsedRecordingDocument.writeFitFile(
        targetFile: File,
        sourceBaseName: String,
    ) {
        FitRecordingExporter.export(
            targetFile = targetFile,
            recording = toFitExport(sourceBaseName),
            appVersionCode = BuildConfig.VERSION_CODE,
            appVersionName = BuildConfig.VERSION_NAME,
        )
    }

    private fun ParsedRecordingDocument.toFitExport(sourceBaseName: String): FitRecordingExport {
        val exportTitle = summary?.title?.ifBlank { title.ifBlank { sourceBaseName } } ?: title.ifBlank { sourceBaseName }
        return FitRecordingExport(
            title = exportTitle,
            samples = samples.map { sample ->
                FitRecordingSample(
                    timestampEpochMs = sample.timestampEpochMs,
                    recordingElapsedMs = sample.recordingElapsedMs,
                    travelElapsedMs = sample.travelElapsedMs,
                    tripKm = sample.tripKm,
                    latitudeDeg = sample.latitudeDeg,
                    longitudeDeg = sample.longitudeDeg,
                    speedKmh = sample.speedKmh,
                    altitudeMeters = sample.altitudeMeters,
                )
            },
        )
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun File.toSavedRecordingFile(): SavedRecordingFile {
        val document = runCatching { parseRecordingDocument(this) }.getOrNull()
        return SavedRecordingFile(
            fileName = name,
            sizeBytes = length(),
            modifiedAtEpochMs = lastModified(),
            title = document?.summary?.title.orEmpty(),
            travelDurationMs = document?.summary?.travelDurationMs,
            tripKm = document?.summary?.tripKm,
            maxRollDeg = document?.summary?.maxRollDeg,
            maxPitchDeg = document?.summary?.maxPitchDeg,
            maxAccelG = document?.summary?.maxAccelG,
            maxDecelG = document?.summary?.maxDecelG,
        )
    }

    private companion object {
        val XmlTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val XmlDocBuilderFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
        val XmlTransformerFactory: javax.xml.transform.TransformerFactory = javax.xml.transform.TransformerFactory.newInstance()
    }

    private data class ParsedRecordingDocument(
        val title: String,
        val summary: ParsedRecordingSummary?,
        val samples: List<ParsedRecordingSample>,
    )

    private data class ParsedRecordingSummary(
        val title: String,
        val travelDurationMs: Long?,
        val tripKm: Double?,
        val maxRollDeg: Double?,
        val maxPitchDeg: Double?,
        val maxAccelG: Double?,
        val maxDecelG: Double?,
    )

    private data class ParsedRecordingSample(
        val timestampIso: String,
        val timestampEpochMs: Long,
        val recordingElapsedMs: Long,
        val travelElapsedMs: Long,
        val tripKm: Double,
        val latitudeDeg: Double?,
        val longitudeDeg: Double?,
        val speedKmh: Double,
        val altitudeMeters: Double?,
        val rollDeg: Double,
        val pitchDeg: Double,
        val accelG: Double,
        val decelG: Double,
    )

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    private fun Element.childText(tagName: String): String {
        return (getElementsByTagName(tagName).item(0) as? Element)?.textContent.orEmpty()
    }

    private fun parseRecordingTimestampEpochMs(timestampIso: String): Long {
        return LocalDateTime.parse(timestampIso, XmlTimestampFormatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
