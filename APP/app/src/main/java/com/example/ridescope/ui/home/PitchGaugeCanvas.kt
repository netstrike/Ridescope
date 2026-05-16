package com.example.ridescope.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

internal const val PitchGaugeMinDeg = 0f
internal const val PitchGaugeStartAngle = 180f

internal val RideScopePitchGaugeStudioConfig = GaugeStudioConfig(
    baseHeight = 208.dp,
    widthRatio = 1.0f,
    layoutTop = 319.dp,
    layoutOffsetY = (-44).dp,
    transform = GaugeTransform(offsetYModel = 24f, scale = 3.4f, textScale = 2.8f),
)

@Composable
internal fun PitchGauge(
    pitchDeg: Float,
    pitchMaxDeg: Float,
    modifier: Modifier = Modifier,
) {
    val transform = RideScopePitchGaugeStudioConfig.transform
    // rememberUpdatedState makes pitchDeg readable in draw phase without invalidating the cache
    val pitchDegState = rememberUpdatedState(pitchDeg)

    key(pitchMaxDeg) {
        Spacer(modifier = modifier.clipToBounds().drawWithCache {
            // Runs only on size change (or key reset). All sin/cos for ticks computed here.
            val offset = Offset(
                x = size.width * (transform.offsetXModel / GaugeModelWidth),
                y = size.height * (transform.offsetYModel / GaugeModelHeight),
            )
            val radius = (kotlin.math.min(size.width * 0.28f, size.height * 0.34f) * transform.scale)
                .coerceAtMost(kotlin.math.min(size.width, size.height) * 0.76f)
            val center = Offset(
                x = size.width * 0.88f + offset.x,
                y = size.height * 0.87f + offset.y,
            )
            val bandWidth = radius * 0.13f
            val rimWidth = radius * 0.024f
            val bandRadius = radius * (113f / 128f)
            val tickBaseRadius = radius - (bandWidth / 2f) - radius * 0.01f
            val rect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
            val bandRect = Rect(
                center.x - bandRadius,
                center.y - bandRadius,
                center.x + bandRadius,
                center.y + bandRadius,
            )
            val pitchSweepAngle = pitchMaxDeg
            val trackStroke = Stroke(width = bandWidth, cap = StrokeCap.Butt)
            val segmentStroke = Stroke(width = bandWidth, cap = StrokeCap.Butt)
            val rimStroke = Stroke(width = rimWidth, cap = StrokeCap.Butt)
            val pitchLabelValues = buildPitchGaugeLabelValues(pitchMaxDeg.toInt())

            // Pre-compute all tick positions
            val ticks = buildList {
                for (deg in PitchGaugeMinDeg.toInt()..pitchMaxDeg.toInt() step 5) {
                    val angle = pitchAngle(deg.toFloat(), pitchMaxDeg)
                    val major = deg % 10 == 0
                    val label = deg in pitchLabelValues
                    add(
                        CachedTickMark(
                            outer = polar(center, tickBaseRadius, angle),
                            inner = polar(
                                center,
                                tickBaseRadius - if (major) radius * 0.10f else radius * 0.06f,
                                angle,
                            ),
                            labelPos = if (label) polar(center, radius + radius * 0.10f, angle) else null,
                            labelText = deg.toString(),
                            major = major,
                            color = GaugeWhite.copy(alpha = if (major) 0.88f else 0.68f),
                            labelColor = GaugeWhite.copy(alpha = 0.86f),
                            strokeWidth = if (major) radius * 0.022f else radius * 0.010f,
                        ),
                    )
                }
            }

            onDrawBehind {
                val currentPitch = pitchDegState.value
                val clampedPitch = currentPitch.coerceIn(PitchGaugeMinDeg, pitchMaxDeg)
                val zeroAngle = pitchAngle(PitchGaugeMinDeg, pitchMaxDeg)
                val currentAngle = pitchAngle(clampedPitch, pitchMaxDeg)
                val activeSweep = currentAngle - zeroAngle

                drawArc(
                    color = GaugeTrack.copy(alpha = 0.94f),
                    startAngle = PitchGaugeStartAngle,
                    sweepAngle = pitchSweepAngle,
                    useCenter = false,
                    topLeft = bandRect.topLeft,
                    size = bandRect.size,
                    style = trackStroke,
                )

                if (clampedPitch > 0.2f) {
                    val segmentCount = maxOf(10, (activeSweep / 3.0f).roundToInt())
                    val segmentSweep = activeSweep / segmentCount
                    repeat(segmentCount) { index ->
                        val startFraction = index / segmentCount.toFloat()
                        val midFraction = (startFraction + (index + 1) / segmentCount.toFloat()) / 2f
                        val segmentStart = zeroAngle + activeSweep * startFraction
                        val segmentColor = rollHeatColor(
                            (clampedPitch * midFraction / pitchMaxDeg).coerceIn(0f, 1f),
                        )
                        drawArc(
                            color = segmentColor,
                            startAngle = segmentStart,
                            sweepAngle = segmentSweep,
                            useCenter = false,
                            topLeft = bandRect.topLeft,
                            size = bandRect.size,
                            style = segmentStroke,
                        )
                    }
                }

                drawArc(
                    color = GaugeWhite.copy(alpha = 0.92f),
                    startAngle = PitchGaugeStartAngle,
                    sweepAngle = pitchSweepAngle,
                    useCenter = false,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = rimStroke,
                )

                // Draw pre-computed ticks — no trig calls here
                for (tick in ticks) {
                    drawLine(
                        color = tick.color,
                        start = tick.inner,
                        end = tick.outer,
                        strokeWidth = tick.strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    tick.labelPos?.let { pos ->
                        drawCenteredText(
                            text = tick.labelText,
                            position = pos,
                            color = tick.labelColor,
                            sizeSp = 20.0f * transform.scaleLabelTextScale,
                            bold = false,
                        )
                    }
                }

                // Pointer: only 2 polar() calls remain in the draw phase
                val pointerOuter = polar(center, radius * 0.88f, currentAngle)
                val pointerInner = polar(center, radius * 0.62f, currentAngle)
                val pointerColor = rollHeatColor((clampedPitch / pitchMaxDeg).coerceIn(0f, 1f))
                drawLine(
                    color = pointerColor.copy(alpha = 0.25f),
                    start = pointerInner,
                    end = pointerOuter,
                    strokeWidth = radius * 0.090f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = pointerColor,
                    start = pointerInner,
                    end = pointerOuter,
                    strokeWidth = radius * 0.030f,
                    cap = StrokeCap.Round,
                )

                drawSkewedCenteredText(
                    text = formatPositiveDegrees(currentPitch),
                    position = Offset(center.x - radius * 0.1f, center.y - radius * 0.15f),
                    color = GaugeWhite,
                    sizeSp = 25f * transform.textScale,
                    bold = true,
                    skewX = -0.1f,
                )
            }
        })
    }
}

// kept internal for potential external use within the package
internal fun DrawScope.drawPitchGauge(
    center: Offset,
    radius: Float,
    pitchDeg: Float,
    pitchGaugeMaxDeg: Float,
    scaleLabelTextScale: Float = GraduatedScaleLabelTextScale,
) {
    val rect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
    val bandRadius = radius * (113f / 128f)
    val bandRect = Rect(
        center.x - bandRadius,
        center.y - bandRadius,
        center.x + bandRadius,
        center.y + bandRadius,
    )
    val bandWidth = radius * 0.13f
    val rimWidth = radius * 0.024f
    val clampedPitch = pitchDeg.coerceIn(PitchGaugeMinDeg, pitchGaugeMaxDeg)
    val zeroAngle = pitchAngle(PitchGaugeMinDeg, pitchGaugeMaxDeg)
    val currentAngle = pitchAngle(clampedPitch, pitchGaugeMaxDeg)
    val activeSweep = currentAngle - zeroAngle
    val pitchSweepAngle = pitchGaugeMaxDeg
    val pitchLabelValues = buildPitchGaugeLabelValues(pitchGaugeMaxDeg.toInt())
    drawArc(
        color = GaugeTrack.copy(alpha = 0.94f),
        startAngle = PitchGaugeStartAngle,
        sweepAngle = pitchSweepAngle,
        useCenter = false,
        topLeft = bandRect.topLeft,
        size = bandRect.size,
        style = Stroke(width = bandWidth, cap = StrokeCap.Butt),
    )
    if (clampedPitch > 0.2f) {
        val segmentCount = maxOf(10, (activeSweep / 3.0f).roundToInt())
        val segmentSweep = activeSweep / segmentCount
        repeat(segmentCount) { index ->
            val startFraction = index / segmentCount.toFloat()
            val endFraction = (index + 1) / segmentCount.toFloat()
            val segmentMidFraction = (startFraction + endFraction) / 2f
            val segmentStart = zeroAngle + (activeSweep * startFraction)
            val segmentPitch = clampedPitch * segmentMidFraction
            val segmentColor = rollHeatColor((segmentPitch / pitchGaugeMaxDeg).coerceIn(0f, 1f))
            drawArc(
                color = segmentColor,
                startAngle = segmentStart,
                sweepAngle = segmentSweep,
                useCenter = false,
                topLeft = bandRect.topLeft,
                size = bandRect.size,
                style = Stroke(width = bandWidth, cap = StrokeCap.Butt),
            )
        }
    }
    drawArc(
        color = GaugeWhite.copy(alpha = 0.92f),
        startAngle = PitchGaugeStartAngle,
        sweepAngle = pitchSweepAngle,
        useCenter = false,
        topLeft = rect.topLeft,
        size = rect.size,
        style = Stroke(width = rimWidth, cap = StrokeCap.Butt),
    )
    for (deg in PitchGaugeMinDeg.toInt()..pitchGaugeMaxDeg.toInt() step 5) {
        val angle = pitchAngle(deg.toFloat(), pitchGaugeMaxDeg)
        val major = deg % 10 == 0
        val label = deg in pitchLabelValues
        val tickBaseRadius = radius - (bandWidth / 2f) - radius * 0.01f
        val tickOuter = polar(center, tickBaseRadius, angle)
        val tickInner = polar(center, tickBaseRadius - if (major) radius * 0.10f else radius * 0.06f, angle)
        drawLine(
            color = GaugeWhite.copy(alpha = if (major) 0.88f else 0.68f),
            start = tickInner,
            end = tickOuter,
            strokeWidth = if (major) radius * 0.022f else radius * 0.010f,
            cap = StrokeCap.Round,
        )
        if (label) {
            drawCenteredText(
                text = deg.toString(),
                position = polar(center, radius + radius * 0.10f, angle),
                color = GaugeWhite.copy(alpha = 0.86f),
                sizeSp = 20.0f * scaleLabelTextScale,
                bold = false,
            )
        }
    }
    val pointerOuter = polar(center, radius * 0.88f, currentAngle)
    val pointerInner = polar(center, radius * 0.62f, currentAngle)
    val pointerColor = rollHeatColor((clampedPitch / pitchGaugeMaxDeg).coerceIn(0f, 1f))
    drawLine(
        color = pointerColor.copy(alpha = 0.25f),
        start = pointerInner,
        end = pointerOuter,
        strokeWidth = radius * 0.090f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = pointerColor,
        start = pointerInner,
        end = pointerOuter,
        strokeWidth = radius * 0.030f,
        cap = StrokeCap.Round,
    )
}

private fun pitchAngle(value: Float, pitchGaugeMaxDeg: Float): Float {
    val normalized = (value.coerceIn(PitchGaugeMinDeg, pitchGaugeMaxDeg) - PitchGaugeMinDeg) /
        (pitchGaugeMaxDeg - PitchGaugeMinDeg)
    return PitchGaugeStartAngle + (normalized * pitchGaugeMaxDeg)
}

private fun formatPositiveDegrees(value: Float): String {
    val displayValue = value.takeIf { it >= 0.05f } ?: 0f
    return String.format(Locale.US, "%.1f\u00B0", displayValue)
}

private fun buildPitchGaugeLabelValues(maxDeg: Int): Set<Int> {
    val labelStep = if (maxDeg <= 75) 15 else 30
    val labels = mutableSetOf<Int>()
    var current = 0
    while (current <= maxDeg) {
        labels += current
        current += labelStep
    }
    labels += maxDeg
    return labels
}

@Preview(name = "Pitch Gauge", group = "Telemetry", showBackground = true, backgroundColor = 0xFF090B0F, widthDp = 412, heightDp = 360)
@Composable
private fun PitchGaugePreview() {
    val gauge = RideScopePitchGaugeStudioConfig
    TelemetryPreviewFrame {
        PitchGauge(
            pitchDeg = TelemetryPreviewState.pitchDeg.toFloat(),
            pitchMaxDeg = TelemetryPreviewState.gaugeConfig.pitchMaxDeg.toFloat(),
            modifier = Modifier
                .width(gauge.previewWidth())
                .height(gauge.previewHeight()),
        )
    }
}
