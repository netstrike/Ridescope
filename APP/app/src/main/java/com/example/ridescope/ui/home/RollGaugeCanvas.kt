package com.example.ridescope.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val RollGaugeStartAngle = 180f
internal const val RollGaugeSweepAngle = 180f

internal val RideScopeRollGaugeStudioConfig = GaugeStudioConfig(
    baseHeight = 136.dp,
    widthRatio = 1.6f,
    layoutTop = 62.dp,
    layoutOffsetY = (-56).dp,
    transform = GaugeTransform(offsetYModel = -10f, scale = 1.62f, textScale = 1.62f),
)

@Composable
internal fun RollGauge(
    rollDeg: Float,
    rollLimitDeg: Float,
    modifier: Modifier = Modifier,
) {
    val transform = RideScopeRollGaugeStudioConfig.transform
    // rememberUpdatedState makes rollDeg readable in the draw phase without invalidating the cache
    val rollDegState = rememberUpdatedState(rollDeg)

    key(rollLimitDeg) {
        Spacer(modifier = modifier.drawWithCache {
            // This block runs only when size changes (or when key resets due to rollLimitDeg change).
            // All sin/cos calls for tick positions happen here, not every frame.
            val offset = Offset(
                x = size.width * (transform.offsetXModel / GaugeModelWidth),
                y = size.height * (transform.offsetYModel / GaugeModelHeight),
            )
            val radius = (kotlin.math.min(size.width * 0.24f, size.height * 0.4f) * transform.scale)
                .coerceAtMost(size.height * 0.96f)
            val center = Offset(
                x = size.width * 0.5f + offset.x,
                y = size.height * 0.96f + offset.y,
            )
            val rollMinDeg = -rollLimitDeg
            // Arc proportional to limit: sweep = limit × 2, centered at 270° (top of arch)
            val gaugeStartAngle = 270f - rollLimitDeg
            val gaugeSweepAngle = rollLimitDeg * 2f
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
            val trackStroke = Stroke(width = bandWidth, cap = StrokeCap.Round)
            val segmentStroke = Stroke(width = bandWidth, cap = StrokeCap.Butt)
            val rimStroke = Stroke(width = rimWidth, cap = StrokeCap.Round)

            // Pre-compute all tick positions (eliminates ~111 trig ops/frame from draw phase)
            val ticks = buildList {
                for (deg in rollMinDeg.toInt()..rollLimitDeg.toInt() step 5) {
                    val angle = leanAngle(deg.toFloat(), rollLimitDeg)
                    val major = deg % 10 == 0
                    add(
                        CachedTickMark(
                            outer = polar(center, tickBaseRadius, angle),
                            inner = polar(
                                center,
                                tickBaseRadius - if (major) radius * 0.10f else radius * 0.06f,
                                angle,
                            ),
                            labelPos = if (major) polar(center, radius + radius * 0.12f, angle) else null,
                            labelText = deg.toString(),
                            major = major,
                            color = if (deg == 0) GaugeWhite
                                    else GaugeWhite.copy(alpha = if (major) 0.88f else 0.68f),
                            labelColor = GaugeWhite.copy(alpha = if (deg == 0) 1f else 0.86f),
                            strokeWidth = if (major) radius * 0.022f else radius * 0.010f,
                        ),
                    )
                }
            }

            onDrawBehind {
                // rollDegState.value read here → tracked in draw phase, not cache phase
                val currentRoll = rollDegState.value
                val clampedRoll = currentRoll.coerceIn(rollMinDeg, rollLimitDeg)
                val zeroAngle = leanAngle(0f, rollLimitDeg)
                val currentAngle = leanAngle(clampedRoll, rollLimitDeg)
                val activeSweep = currentAngle - zeroAngle

                drawArc(
                    color = GaugeTrack.copy(alpha = 0.94f),
                    startAngle = gaugeStartAngle,
                    sweepAngle = gaugeSweepAngle,
                    useCenter = false,
                    topLeft = bandRect.topLeft,
                    size = bandRect.size,
                    style = trackStroke,
                )

                if (abs(clampedRoll) > 0.2f) {
                    val segmentCount = maxOf(12, (abs(activeSweep) / 2.5f).roundToInt())
                    val segmentSweep = activeSweep / segmentCount
                    repeat(segmentCount) { index ->
                        val startFraction = index / segmentCount.toFloat()
                        val midFraction = (startFraction + (index + 1) / segmentCount.toFloat()) / 2f
                        val segmentStart = zeroAngle + activeSweep * startFraction
                        val segmentColor = rollHeatColor(
                            (abs(clampedRoll) * midFraction / rollLimitDeg).coerceIn(0f, 1f),
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
                    startAngle = gaugeStartAngle,
                    sweepAngle = gaugeSweepAngle,
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
                        drawLabelText(
                            text = tick.labelText,
                            position = pos,
                            color = tick.labelColor,
                            sizeSp = 14.0f * transform.scaleLabelTextScale,
                            bold = false,
                        )
                    }
                }

                // Pointer: only 2 polar() calls remain in the draw phase
                val pointerOuter = polar(center, radius * 0.88f, currentAngle)
                val pointerInner = polar(center, radius * 0.62f, currentAngle)
                val pointerColor = rollHeatColor(abs(clampedRoll) / rollLimitDeg)
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
                    text = formatDegrees(currentRoll),
                    position = Offset(center.x, center.y - radius * 0.12f),
                    color = GaugeWhite,
                    sizeSp = 40f * transform.textScale,
                    bold = true,
                    skewX = -0.15f,
                )
            }
        })
    }
}

// kept internal for potential external use within the package
internal fun DrawScope.drawLeanBandGauge(
    center: Offset,
    radius: Float,
    rollDeg: Float,
    rollLimitDeg: Float,
    scaleLabelTextScale: Float = GraduatedScaleLabelTextScale,
) {
    val rollMinDeg = -rollLimitDeg
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
    val gaugeStartAngle = 270f - rollLimitDeg
    val gaugeSweepAngle = rollLimitDeg * 2f
    drawArc(
        color = GaugeTrack.copy(alpha = 0.94f),
        startAngle = gaugeStartAngle,
        sweepAngle = gaugeSweepAngle,
        useCenter = false,
        topLeft = bandRect.topLeft,
        size = bandRect.size,
        style = Stroke(width = bandWidth, cap = StrokeCap.Round),
    )
    val clampedRoll = rollDeg.coerceIn(rollMinDeg, rollLimitDeg)
    val zeroAngle = leanAngle(0f, rollLimitDeg)
    val currentAngle = leanAngle(clampedRoll, rollLimitDeg)
    val activeSweep = currentAngle - zeroAngle
    if (abs(clampedRoll) > 0.2f) {
        val segmentCount = maxOf(12, (abs(activeSweep) / 2.5f).roundToInt())
        val segmentSweep = activeSweep / segmentCount
        repeat(segmentCount) { index ->
            val startFraction = index / segmentCount.toFloat()
            val endFraction = (index + 1) / segmentCount.toFloat()
            val segmentMidFraction = (startFraction + endFraction) / 2f
            val segmentStart = zeroAngle + (activeSweep * startFraction)
            val segmentRollAbs = abs(clampedRoll) * segmentMidFraction
            val segmentColor = rollHeatColor((segmentRollAbs / rollLimitDeg).coerceIn(0f, 1f))
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
        startAngle = gaugeStartAngle,
        sweepAngle = gaugeSweepAngle,
        useCenter = false,
        topLeft = rect.topLeft,
        size = rect.size,
        style = Stroke(width = rimWidth, cap = StrokeCap.Round),
    )
    for (deg in rollMinDeg.toInt()..rollLimitDeg.toInt() step 5) {
        val angle = leanAngle(deg.toFloat(), rollLimitDeg)
        val major = deg % 10 == 0
        val tickBaseRadius = radius - (bandWidth / 2f) - radius * 0.01f
        val tickOuter = polar(center, tickBaseRadius, angle)
        val tickInner = polar(center, tickBaseRadius - if (major) radius * 0.10f else radius * 0.06f, angle)
        val tickColor = if (deg == 0) GaugeWhite else GaugeWhite.copy(alpha = if (major) 0.88f else 0.68f)
        drawLine(
            color = tickColor,
            start = tickInner,
            end = tickOuter,
            strokeWidth = if (major) radius * 0.022f else radius * 0.010f,
            cap = StrokeCap.Round,
        )
        if (major) {
            drawLabelText(
                text = deg.toString(),
                position = polar(center, radius + radius * 0.12f, angle),
                color = GaugeWhite.copy(alpha = if (deg == 0) 1f else 0.86f),
                sizeSp = 14.0f * scaleLabelTextScale,
                bold = false,
            )
        }
    }
    val pointerOuter = polar(center, radius * 0.88f, currentAngle)
    val pointerInner = polar(center, radius * 0.62f, currentAngle)
    val pointerColor = rollHeatColor(abs(clampedRoll) / rollLimitDeg)
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

private fun leanAngle(value: Float, rollLimitDeg: Float): Float {
    val rollMinDeg = -rollLimitDeg
    val normalized = (value.coerceIn(rollMinDeg, rollLimitDeg) - rollMinDeg) /
        (rollLimitDeg - rollMinDeg)
    val startAngle = 270f - rollLimitDeg
    val sweepAngle = rollLimitDeg * 2f
    return startAngle + (normalized * sweepAngle)
}

private fun formatDegrees(value: Float): String {
    val displayValue = if (abs(value) < 0.05f) 0f else value
    return String.format(Locale.US, "%.1f\u00B0", displayValue)
}

@Preview(name = "Roll Gauge", group = "Telemetry", showBackground = true, backgroundColor = 0xFF090B0F, widthDp = 355, heightDp = 238)
@Composable
private fun RollGaugePreview() {
    val gauge = RideScopeRollGaugeStudioConfig
    TelemetryPreviewFrame {
        RollGauge(
            rollDeg = TelemetryPreviewState.rollDeg.toFloat(),
            rollLimitDeg = TelemetryPreviewState.gaugeConfig.rollLimitDeg.toFloat(),
            modifier = Modifier
                .width(gauge.previewWidth())
                .height(gauge.previewHeight()),
        )
    }
}
