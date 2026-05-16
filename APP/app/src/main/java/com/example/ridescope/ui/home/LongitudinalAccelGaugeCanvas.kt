package com.example.ridescope.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ridescope.data.model.LongitudinalAccelUnit
import com.example.ridescope.data.model.TelemetryGaugeConfig
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GravityMs2 = 9.80665f

// Static dim colors used for the gradient fill — pre-defined to avoid per-frame allocation
private val AccelDimColor = Color(0xFF27421D)
private val DecelDimColor = Color(0xFF6E1B28)

internal val RideScopeLongitudinalAccelGaugeStudioConfig = GaugeStudioConfig(
    baseHeight = 54.dp,
    widthRatio = 3.6f,
    layoutTop = 222.dp,
    transform = GaugeTransform(offsetYModel = 16f, scale = 1.8f, textScale = 1.8f),
)

@Composable
internal fun LongitudinalAccelGauge(
    accelG: Float,
    gaugeConfig: TelemetryGaugeConfig,
    modifier: Modifier = Modifier,
) {
    val transform = RideScopeLongitudinalAccelGaugeStudioConfig.transform
    val accelGState = rememberUpdatedState(accelG)

    // key on config values that affect static geometry; accelG is read via rememberUpdatedState
    key(gaugeConfig.longitudinalAccelLimitG, gaugeConfig.longitudinalAccelUnit) {
        Spacer(modifier = modifier.drawWithCache {
            // Cache phase: runs only when size or key changes.
            val offset = Offset(
                x = size.width * (transform.offsetXModel / GaugeModelWidth),
                y = size.height * (transform.offsetYModel / GaugeModelHeight),
            )
            val rect = centeredRect(
                center = Offset(
                    x = size.width * 0.5f + offset.x,
                    y = size.height * 0.40f + offset.y,
                ),
                width = (size.width * 0.48f * transform.scale).coerceAtMost(size.width * 0.96f),
                height = (size.height * 0.30f * transform.scale).coerceAtMost(size.height * 0.76f),
            )
            val barRect = Rect(
                left = rect.left + rect.width * 0.025f,
                top = rect.top + rect.height * 0.60f,
                right = rect.right - rect.width * 0.025f,
                bottom = rect.top + rect.height * 0.84f,
            )
            val centerX = barRect.center.x
            val halfBarWidth = barRect.width / 2f
            val limitG = gaugeConfig.longitudinalAccelLimitG.toFloat()
            val displayUnit = gaugeConfig.longitudinalAccelUnit
            val sideLabelY = barRect.center.y
            val sideLabelSize = 35f * transform.scaleLabelTextScale
            val sideLabelInset = rect.width * 0.02f
            val centerLineHalfLen = barRect.height * 0.50f
            val centerLineWidth = barRect.height * 0.12f
            val markerWidth = barRect.height * 0.26f
            val markerHeight = barRect.height * 2.0f

            // Static brushes — only depend on barRect geometry, cached here
            val bgBrush = Brush.verticalGradient(
                colors = listOf(Color(0xFF1F252C), Color(0xFF0F1318)),
                startY = barRect.top,
                endY = barRect.bottom,
            )
            val accelTintBrush = Brush.horizontalGradient(
                colors = listOf(Color(0xFF8DFF5C).copy(alpha = 0.62f), Color(0xFF3DFF8B).copy(alpha = 0.22f)),
                startX = barRect.left,
                endX = centerX,
            )
            val decelTintBrush = Brush.horizontalGradient(
                colors = listOf(Color(0xFFFF6C86).copy(alpha = 0.22f), Color(0xFFFF244B).copy(alpha = 0.62f)),
                startX = centerX,
                endX = barRect.right,
            )
            val roundCorner = CornerRadius(999f, 999f)

            onDrawBehind {
                val currentAccelG = accelGState.value
                val normalizedValue = (currentAccelG / limitG).coerceIn(-1f, 1f)
                val accelMagnitude = abs(normalizedValue)
                val markerX = centerX - normalizedValue * halfBarWidth

                // Dynamic colors — computed from accelMagnitude each frame (cheap lerp)
                val leftBrightColor = lerp(Color(0xFF4F8D38), Color(0xFF8EFF69), accelMagnitude)
                val rightBrightColor = lerp(Color(0xFFCF334A), Color(0xFFFF3B5A), accelMagnitude)
                val markerColor = when {
                    accelMagnitude < 0.02f -> GaugeWhite.copy(alpha = 0.80f)
                    currentAccelG > 0f -> leftBrightColor
                    else -> rightBrightColor
                }

                drawSkewedCenteredText(
                    text = formatLongitudinalAccelValue(currentAccelG, displayUnit),
                    position = Offset(rect.center.x, rect.top + rect.height * 0.20f),
                    color = GaugeWhite,
                    sizeSp = 70f * transform.textScale,
                    bold = true,
                    skewX = -0.15f,
                )
                drawRightAlignedText(
                    text = "+${formatLongitudinalAccelScaleValue(limitG, displayUnit)}",
                    position = Offset(barRect.left - sideLabelInset, sideLabelY),
                    color = GaugeWhite,
                    sizeSp = sideLabelSize,
                    bold = false,
                )
                drawLeftAlignedText(
                    text = "-${formatLongitudinalAccelScaleValue(limitG, displayUnit)}",
                    position = Offset(barRect.right + sideLabelInset, sideLabelY),
                    color = GaugeWhite,
                    sizeSp = sideLabelSize,
                    bold = false,
                )

                // Static bar layers — use cached brushes
                drawRoundRect(
                    brush = bgBrush,
                    topLeft = barRect.topLeft,
                    size = barRect.size,
                    cornerRadius = roundCorner,
                )
                drawRoundRect(
                    brush = accelTintBrush,
                    topLeft = barRect.topLeft,
                    size = Size(halfBarWidth, barRect.height),
                    cornerRadius = roundCorner,
                )
                drawRoundRect(
                    brush = decelTintBrush,
                    topLeft = Offset(centerX, barRect.top),
                    size = Size(halfBarWidth, barRect.height),
                    cornerRadius = roundCorner,
                )

                // Dynamic accent fill — brush depends on markerX (changes every frame)
                if (currentAccelG > 0.02f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(leftBrightColor, AccelDimColor),
                            startX = markerX,
                            endX = centerX,
                        ),
                        topLeft = Offset(markerX, barRect.top),
                        size = Size(centerX - markerX, barRect.height),
                        cornerRadius = roundCorner,
                    )
                } else if (currentAccelG < -0.02f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(DecelDimColor, rightBrightColor),
                            startX = centerX,
                            endX = markerX,
                        ),
                        topLeft = Offset(centerX, barRect.top),
                        size = Size(markerX - centerX, barRect.height),
                        cornerRadius = roundCorner,
                    )
                }

                drawLine(
                    color = GaugeWhite.copy(alpha = 0.30f),
                    start = Offset(centerX, barRect.top - centerLineHalfLen),
                    end = Offset(centerX, barRect.bottom + centerLineHalfLen),
                    strokeWidth = centerLineWidth,
                    cap = StrokeCap.Round,
                )
                drawRoundRect(
                    color = GaugeWhite.copy(alpha = 0.22f),
                    topLeft = Offset(markerX - markerWidth * 1.55f, barRect.center.y - markerHeight / 2f),
                    size = Size(markerWidth * 3.1f, markerHeight),
                    cornerRadius = roundCorner,
                )
                drawRoundRect(
                    color = markerColor,
                    topLeft = Offset(markerX - markerWidth, barRect.center.y - markerHeight * 0.28f),
                    size = Size(markerWidth * 2f, markerHeight * 0.56f),
                    cornerRadius = roundCorner,
                )
            }
        })
    }
}

// kept internal for potential external use within the package
internal fun DrawScope.drawLongitudinalAccelGauge(
    rect: Rect,
    accelG: Float,
    limitG: Float,
    displayUnit: LongitudinalAccelUnit,
    textScale: Float = 1f,
    scaleLabelTextScale: Float = GraduatedScaleLabelTextScale,
) {
    val barRect = Rect(
        left = rect.left + rect.width * 0.025f,
        top = rect.top + rect.height * 0.60f,
        right = rect.right - rect.width * 0.025f,
        bottom = rect.top + rect.height * 0.84f,
    )
    val centerX = barRect.center.x
    val halfBarWidth = barRect.width / 2f
    val normalizedValue = (accelG / limitG).coerceIn(-1f, 1f)
    val accelMagnitude = abs(normalizedValue)
    val markerX = centerX - (normalizedValue * halfBarWidth)
    val markerWidth = barRect.height * 0.26f
    val markerHeight = barRect.height * 2.0f
    val leftBrightColor = lerp(Color(0xFF4F8D38), Color(0xFF8EFF69), accelMagnitude)
    val rightBrightColor = lerp(Color(0xFFCF334A), Color(0xFFFF3B5A), accelMagnitude)
    val markerColor = when {
        accelMagnitude < 0.02f -> GaugeWhite.copy(alpha = 0.80f)
        accelG > 0f -> leftBrightColor
        else -> rightBrightColor
    }
    drawSkewedCenteredText(
        text = formatLongitudinalAccelValue(accelG, displayUnit),
        position = Offset(rect.center.x, rect.top + rect.height * 0.20f),
        color = GaugeWhite,
        sizeSp = 70f * textScale,
        bold = true,
        skewX = -0.15f,
    )
    val sideLabelY = barRect.center.y
    val sideLabelSize = 35f * scaleLabelTextScale
    val sideLabelInset = rect.width * 0.02f
    drawRightAlignedText(
        text = "+${formatLongitudinalAccelScaleValue(limitG, displayUnit)}",
        position = Offset(barRect.left - sideLabelInset, sideLabelY),
        color = GaugeWhite,
        sizeSp = sideLabelSize,
        bold = false,
    )
    drawLeftAlignedText(
        text = "-${formatLongitudinalAccelScaleValue(limitG, displayUnit)}",
        position = Offset(barRect.right + sideLabelInset, sideLabelY),
        color = GaugeWhite,
        sizeSp = sideLabelSize,
        bold = false,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF1F252C), Color(0xFF0F1318)),
            startY = barRect.top,
            endY = barRect.bottom,
        ),
        topLeft = barRect.topLeft,
        size = barRect.size,
        cornerRadius = CornerRadius(999f, 999f),
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF8DFF5C).copy(alpha = 0.62f), Color(0xFF3DFF8B).copy(alpha = 0.22f)),
            startX = barRect.left,
            endX = centerX,
        ),
        topLeft = barRect.topLeft,
        size = Size(halfBarWidth, barRect.height),
        cornerRadius = CornerRadius(999f, 999f),
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFFFF6C86).copy(alpha = 0.22f), Color(0xFFFF244B).copy(alpha = 0.62f)),
            startX = centerX,
            endX = barRect.right,
        ),
        topLeft = Offset(centerX, barRect.top),
        size = Size(halfBarWidth, barRect.height),
        cornerRadius = CornerRadius(999f, 999f),
    )
    if (accelG > 0.02f) {
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(leftBrightColor, AccelDimColor),
                startX = markerX,
                endX = centerX,
            ),
            topLeft = Offset(markerX, barRect.top),
            size = Size(centerX - markerX, barRect.height),
            cornerRadius = CornerRadius(999f, 999f),
        )
    } else if (accelG < -0.02f) {
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(DecelDimColor, rightBrightColor),
                startX = centerX,
                endX = markerX,
            ),
            topLeft = Offset(centerX, barRect.top),
            size = Size(markerX - centerX, barRect.height),
            cornerRadius = CornerRadius(999f, 999f),
        )
    }
    drawLine(
        color = GaugeWhite.copy(alpha = 0.30f),
        start = Offset(centerX, barRect.top - barRect.height * 0.50f),
        end = Offset(centerX, barRect.bottom + barRect.height * 0.50f),
        strokeWidth = barRect.height * 0.12f,
        cap = StrokeCap.Round,
    )
    drawRoundRect(
        color = GaugeWhite.copy(alpha = 0.22f),
        topLeft = Offset(markerX - markerWidth * 1.55f, barRect.center.y - markerHeight / 2f),
        size = Size(markerWidth * 3.1f, markerHeight),
        cornerRadius = CornerRadius(999f, 999f),
    )
    drawRoundRect(
        color = markerColor,
        topLeft = Offset(markerX - markerWidth, barRect.center.y - markerHeight * 0.28f),
        size = Size(markerWidth * 2f, markerHeight * 0.56f),
        cornerRadius = CornerRadius(999f, 999f),
    )
}

private fun formatLongitudinalAccelValue(valueG: Float, unit: LongitudinalAccelUnit): String {
    val displayValue = when (unit) {
        LongitudinalAccelUnit.G -> valueG
        LongitudinalAccelUnit.Ms2 -> valueG * GravityMs2
    }
    val suffix = when (unit) {
        LongitudinalAccelUnit.G -> " G"
        LongitudinalAccelUnit.Ms2 -> " m/s\u00B2"
    }
    return formatSignedNumber(displayValue) + suffix
}

private fun formatLongitudinalAccelScaleValue(limitG: Float, unit: LongitudinalAccelUnit): String {
    val displayValue = when (unit) {
        LongitudinalAccelUnit.G -> limitG
        LongitudinalAccelUnit.Ms2 -> limitG * GravityMs2
    }
    return formatGaugeScaleNumber(displayValue)
}

private fun formatSignedNumber(value: Float): String = when {
    abs(value) < 0.05f -> "0.0"
    value > 0f -> String.format(Locale.US, "+%.1f", value)
    else -> String.format(Locale.US, "%.1f", value)
}

private fun formatGaugeScaleNumber(value: Float): String {
    return if (abs(value - value.roundToInt()) < 0.05f) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

@Preview(name = "Accel/Decel Gauge", group = "Telemetry", showBackground = true, backgroundColor = 0xFF090B0F, widthDp = 520, heightDp = 180)
@Composable
private fun LongitudinalAccelGaugePreview() {
    val gauge = RideScopeLongitudinalAccelGaugeStudioConfig
    TelemetryPreviewFrame {
        LongitudinalAccelGauge(
            accelG = TelemetryPreviewState.longitudinalAccelG.toFloat(),
            gaugeConfig = TelemetryPreviewState.gaugeConfig,
            modifier = Modifier
                .fillMaxWidth()
                .height(gauge.previewHeight()),
        )
    }
}
