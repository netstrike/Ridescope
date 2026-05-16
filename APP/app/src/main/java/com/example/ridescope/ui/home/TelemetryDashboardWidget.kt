package com.example.ridescope.ui.home

import android.graphics.Paint
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ridescope.data.model.RecordingPeaks
import com.example.ridescope.data.model.RecordingStatus
import com.example.ridescope.data.model.RecordingUiState
import com.example.ridescope.data.model.TelemetryGaugeConfig
import com.example.ridescope.data.model.TelemetryGpsSource
import com.example.ridescope.data.model.TelemetryUiState
import com.example.ridescope.ui.theme.RideScopeTheme
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal val GaugeWhite = Color(0xFFF8FAFC)
internal val GaugeGreen = Color(0xFF6CDB48)
internal val GaugeBlue = Color(0xFF3AAEFF)
internal val GaugeYellow = Color(0xFFF2E84A)
internal val GaugeMuted = Color(0xFFA5AFBA)
internal val GaugeTrack = Color(0xFF1E232A)

private val CarbonStripeLight = Color.White.copy(alpha = 0.045f)
private val CarbonStripeShadow = Color.Black.copy(alpha = 0.18f)
private val CarbonBgBrush: Brush = Brush.verticalGradient(listOf(Color(0xFF1D2127), Color(0xFF0D1015)))
private val reusablePaint = Paint()

internal data class CachedTickMark(
    val outer: Offset,
    val inner: Offset,
    val labelPos: Offset?,
    val labelText: String,
    val major: Boolean,
    val color: Color,
    val labelColor: Color,
    val strokeWidth: Float,
)

internal const val GaugeModelWidth = 320f
internal const val GaugeModelHeight = 360f
internal const val GraduatedScaleLabelTextScale = 1.8f
private val StatusIconHorizontalPadding = 6.dp
private val StatusIconVerticalPadding = 2.dp
private val StatusIconSize = 20.dp

internal data class GaugeTransform(
    val offsetXModel: Float = 0f,
    val offsetYModel: Float = 0f,
    val scale: Float = 1f,
    val textScale: Float = 1f,
    val scaleLabelTextScale: Float = GraduatedScaleLabelTextScale,
)

internal data class TelemetryScreenStudioConfig(
    val panelPadding: PaddingValues = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
    val headerOffsetY: Dp = (-4).dp,
    val menuOffsetX: Dp = (-8).dp,
    val statusRowOffsetX: Dp = 8.dp,
    val statusRowOffsetY: Dp = (-8).dp,
    val gaugesTopPadding: Dp = 7.dp,
    val gaugesOffsetY: Dp = (-54).dp,
    val gaugesSpacing: Dp = 8.dp,
    val telemetryPanelCornerRadius: Dp = 28.dp,
)

internal data class GaugeStudioConfig(
    val baseHeight: Dp,
    val widthRatio: Float,
    val layoutTop: Dp = 0.dp,
    val layoutOffsetX: Dp = 0.dp,
    val layoutOffsetY: Dp = 0.dp,
    val transform: GaugeTransform,
)

private data class GaugePlacementSpec(
    val width: Dp,
    val height: Dp,
    val x: Dp,
    val y: Dp,
)

private data class TelemetryGaugeDeckLayoutSpec(
    val roll: GaugePlacementSpec,
    val accel: GaugePlacementSpec,
    val pitch: GaugePlacementSpec,
    val speed: GaugePlacementSpec,
)

internal val RideScopeTelemetryScreenStudioConfig = TelemetryScreenStudioConfig()

internal val TelemetryPreviewState = TelemetryUiState(
    bleConnected = true,
    liveSubscribed = true,
    imuReady = true,
    gpsPermissionGranted = true,
    gpsSource = TelemetryGpsSource.Phone,
    gpsFixAvailable = true,
    rollDeg = 18.4,
    pitchDeg = 7.8,
    longitudinalAccelG = 0.34,
    gpsHeadingDeg = 12.0,
    gpsSpeedKmh = 96.0,
    gpsMaxSpeedKmh = 132.0,
    gpsTripKm = 42.7,
    gpsAltitudeMeters = 314.0,
    gpsAccuracyMeters = 1.8,
    recording = RecordingUiState(
        status = RecordingStatus.Recording,
        totalDurationMs = 1_842_000L,
        travelDurationMs = 1_615_000L,
        tripKm = 42.7,
        peaks = RecordingPeaks(
            speedKmh = 132.0,
            altitudeMeters = 534.0,
            rollDeg = 47.8,
            pitchDeg = 12.4,
            accelG = 0.86,
            decelG = 0.92,
        ),
    ),
)

private enum class StatusChipState {
    Active,
    ActivePhoneFallback,
    Inactive,
    Reconnecting,
}

private data class StatusChipSpec(
    val icon: ImageVector,
    val contentDescription: String,
    val state: StatusChipState,
)

private fun TelemetryUiState.gpsChipState(): StatusChipState = when {
    gpsSource == TelemetryGpsSource.Phone && gpsFixAvailable -> StatusChipState.ActivePhoneFallback
    gpsFixAvailable -> StatusChipState.Active
    gpsSource == TelemetryGpsSource.Phone && gpsPermissionGranted -> StatusChipState.Reconnecting
    else -> StatusChipState.Inactive
}

private fun TelemetryUiState.bleChipState(): StatusChipState = when {
    bleConnected -> StatusChipState.Active
    bleReconnecting -> StatusChipState.Reconnecting
    else -> StatusChipState.Inactive
}

private fun TelemetryUiState.liveChipState(): StatusChipState = when {
    liveSubscribed -> StatusChipState.Active
    bleReconnecting -> StatusChipState.Reconnecting
    else -> StatusChipState.Inactive
}

private fun TelemetryUiState.imuChipState(): StatusChipState =
    if (imuReady) StatusChipState.Active else StatusChipState.Inactive

@Composable
fun TelemetryDashboardWidget(
    state: TelemetryUiState,
    pageMenu: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val screenConfig = RideScopeTelemetryScreenStudioConfig
    val animatedRoll = animateFloatAsState(targetValue = state.rollDeg.toFloat(), label = "roll")
    val animatedPitch = animateFloatAsState(targetValue = state.pitchDeg.toFloat(), label = "pitch")
    val animatedAccel = animateFloatAsState(targetValue = state.longitudinalAccelG.toFloat(), label = "accel")
    val statusChips = remember(
        state.bleConnected, state.bleReconnecting,
        state.liveSubscribed, state.imuReady,
        state.gpsFixAvailable, state.gpsSource, state.gpsPermissionGranted,
    ) {
        listOf(
            StatusChipSpec(Icons.Outlined.Bluetooth, "BLE", state.bleChipState()),
            StatusChipSpec(Icons.Outlined.LocationOn, "GPS", state.gpsChipState()),
            StatusChipSpec(Icons.Outlined.Sensors, "IMU", state.imuChipState()),
            StatusChipSpec(Icons.Outlined.GraphicEq, "Live", state.liveChipState()),
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(screenConfig.telemetryPanelCornerRadius),
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenConfig.panelPadding),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TelemetryDashboardHeader(
                screenConfig = screenConfig,
                pageMenu = pageMenu,
                statusChips = statusChips,
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = screenConfig.gaugesTopPadding)
                    .offset(y = screenConfig.gaugesOffsetY)
                    .weight(1f),
            ) {
                val gaugeDeckLayoutSpec = remember(maxWidth, maxHeight) {
                    calculateTelemetryGaugeDeckLayoutSpec(
                        maxWidth = maxWidth,
                        maxHeight = maxHeight,
                        screenConfig = screenConfig,
                    )
                }

                TelemetryGaugeDeck(
                    rollDeg = animatedRoll.value,
                    rollLimitDeg = state.gaugeConfig.rollLimitDeg.toFloat(),
                    accelG = animatedAccel.value,
                    gaugeConfig = state.gaugeConfig,
                    pitchDeg = animatedPitch.value,
                    pitchMaxDeg = state.gaugeConfig.pitchMaxDeg.toFloat(),
                    gpsFixAvailable = state.gpsFixAvailable,
                    gpsSpeedKmh = state.gpsSpeedKmh.toFloat(),
                    layoutSpec = gaugeDeckLayoutSpec,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun calculateTelemetryGaugeDeckLayoutSpec(
    maxWidth: Dp,
    maxHeight: Dp,
    screenConfig: TelemetryScreenStudioConfig,
): TelemetryGaugeDeckLayoutSpec {
    val spacing = screenConfig.gaugesSpacing
    val bottomContentWidth = (maxWidth - spacing).coerceAtLeast(0.dp)
    val pitchSlotWidth = bottomContentWidth * 0.52f
    val speedSlotWidth = bottomContentWidth * 0.48f
    val bottomLeftCenterX = pitchSlotWidth / 2f
    val bottomRightCenterX = pitchSlotWidth + spacing + (speedSlotWidth / 2f)

    val rollPlacement = buildGaugePlacementSpec(
        gaugeConfig = RideScopeRollGaugeStudioConfig,
        deckHeight = maxHeight,
        slotWidth = maxWidth,
        centerX = maxWidth / 2f,
    )
    val accelPlacement = buildGaugePlacementSpec(
        gaugeConfig = RideScopeLongitudinalAccelGaugeStudioConfig,
        deckHeight = maxHeight,
        slotWidth = maxWidth,
        centerX = maxWidth / 2f,
    )
    val pitchPlacement = buildGaugePlacementSpec(
        gaugeConfig = RideScopePitchGaugeStudioConfig,
        deckHeight = maxHeight,
        slotWidth = pitchSlotWidth,
        centerX = bottomLeftCenterX,
    )
    val speedPlacement = buildGaugePlacementSpec(
        gaugeConfig = RideScopeSpeedGaugeStudioConfig,
        deckHeight = maxHeight,
        slotWidth = speedSlotWidth,
        centerX = bottomRightCenterX,
    )
    val bottomRowBottom = maxOf(
        (pitchPlacement.y + pitchPlacement.height).value,
        (speedPlacement.y + speedPlacement.height).value,
    ).dp

    return TelemetryGaugeDeckLayoutSpec(
        roll = rollPlacement,
        accel = accelPlacement,
        pitch = pitchPlacement.copy(y = bottomRowBottom - pitchPlacement.height),
        speed = speedPlacement.copy(y = bottomRowBottom - speedPlacement.height),
    )
}

private fun buildGaugePlacementSpec(
    gaugeConfig: GaugeStudioConfig,
    deckHeight: Dp,
    slotWidth: Dp,
    centerX: Dp,
): GaugePlacementSpec {
    val idealWidth = gaugeConfig.previewWidth()
    val idealHeight = gaugeConfig.previewHeight()
    val top = gaugeConfig.layoutTop + gaugeConfig.layoutOffsetY
    val availableHeight = (deckHeight - top.coerceAtLeast(0.dp)).coerceAtLeast(0.dp)
    val widthFitScale = if (idealWidth.value > 0f) {
        (slotWidth.value / idealWidth.value).coerceAtMost(1f)
    } else {
        1f
    }
    val heightFitScale = if (idealHeight.value > 0f && availableHeight.value > 0f) {
        (availableHeight.value / idealHeight.value).coerceAtMost(1f)
    } else if (availableHeight.value <= 0f) {
        0f
    } else {
        1f
    }
    val fitScale = minOf(1f, widthFitScale, heightFitScale)
    val width = idealWidth * fitScale
    val height = idealHeight * fitScale

    return GaugePlacementSpec(
        width = width,
        height = height,
        x = centerX - (width / 2f) + gaugeConfig.layoutOffsetX,
        y = top,
    )
}

@Composable
private fun TelemetryDashboardHeader(
    screenConfig: TelemetryScreenStudioConfig,
    pageMenu: (@Composable () -> Unit)?,
    statusChips: List<StatusChipSpec>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = screenConfig.headerOffsetY),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.offset(x = screenConfig.menuOffsetX)) {
            pageMenu?.invoke()
        }
        Row(
            modifier = Modifier.offset(x = screenConfig.statusRowOffsetX, y = screenConfig.statusRowOffsetY),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            statusChips.forEach { chip ->
                StatusChip(
                    icon = chip.icon,
                    contentDescription = chip.contentDescription,
                    state = chip.state,
                )
            }
        }
    }
}

@Composable
private fun TelemetryGaugeDeck(
    rollDeg: Float,
    rollLimitDeg: Float,
    accelG: Float,
    gaugeConfig: TelemetryGaugeConfig,
    pitchDeg: Float,
    pitchMaxDeg: Float,
    gpsFixAvailable: Boolean,
    gpsSpeedKmh: Float,
    layoutSpec: TelemetryGaugeDeckLayoutSpec,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            RollGauge(
                rollDeg = rollDeg,
                rollLimitDeg = rollLimitDeg,
                modifier = Modifier
                    .width(layoutSpec.roll.width)
                    .height(layoutSpec.roll.height),
            )
            LongitudinalAccelGauge(
                accelG = accelG,
                gaugeConfig = gaugeConfig,
                modifier = Modifier
                    .width(layoutSpec.accel.width)
                    .height(layoutSpec.accel.height),
            )
            PitchGauge(
                pitchDeg = pitchDeg,
                pitchMaxDeg = pitchMaxDeg,
                modifier = Modifier
                    .width(layoutSpec.pitch.width)
                    .height(layoutSpec.pitch.height),
            )
            SpeedGauge(
                gpsFixAvailable = gpsFixAvailable,
                gpsSpeedKmh = gpsSpeedKmh,
                modifier = Modifier
                    .width(layoutSpec.speed.width)
                    .height(layoutSpec.speed.height),
            )
        },
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val rollPlaceable = measurables[0].measure(looseConstraints)
        val accelPlaceable = measurables[1].measure(looseConstraints)
        val pitchPlaceable = measurables[2].measure(looseConstraints)
        val speedPlaceable = measurables[3].measure(looseConstraints)
        val contentHeight = maxOf(
            constraints.maxHeight,
            layoutSpec.roll.y.roundToPx() + rollPlaceable.height,
            layoutSpec.accel.y.roundToPx() + accelPlaceable.height,
            layoutSpec.pitch.y.roundToPx() + pitchPlaceable.height,
            layoutSpec.speed.y.roundToPx() + speedPlaceable.height,
        ).coerceAtLeast(constraints.minHeight)

        layout(width = constraints.maxWidth, height = contentHeight) {
            rollPlaceable.placeRelative(
                x = layoutSpec.roll.x.roundToPx(),
                y = layoutSpec.roll.y.roundToPx(),
            )
            accelPlaceable.placeRelative(
                x = layoutSpec.accel.x.roundToPx(),
                y = layoutSpec.accel.y.roundToPx(),
            )
            pitchPlaceable.placeRelative(
                x = layoutSpec.pitch.x.roundToPx(),
                y = layoutSpec.pitch.y.roundToPx(),
            )
            speedPlaceable.placeRelative(
                x = layoutSpec.speed.x.roundToPx(),
                y = layoutSpec.speed.y.roundToPx(),
            )
        }
    }
}

internal fun DrawScope.gaugeTransformOffset(transform: GaugeTransform): Offset = Offset(
    x = size.width * (transform.offsetXModel / GaugeModelWidth),
    y = size.height * (transform.offsetYModel / GaugeModelHeight),
)

internal fun centeredRect(
    center: Offset,
    width: Float,
    height: Float,
): Rect = Rect(
    left = center.x - (width / 2f),
    top = center.y - (height / 2f),
    right = center.x + (width / 2f),
    bottom = center.y + (height / 2f),
)

@Composable
private fun StatusChip(
    icon: ImageVector,
    contentDescription: String,
    state: StatusChipState,
) {
    val transition = rememberInfiniteTransition(label = "$contentDescription-chip")
    val blinkingAlpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "$contentDescription-chip-alpha",
    ).value
    val chipAlpha = if (state == StatusChipState.Reconnecting) blinkingAlpha else 1f
    val contentColor = when (state) {
        StatusChipState.Active -> GaugeBlue
        StatusChipState.ActivePhoneFallback -> GaugeGreen
        StatusChipState.Inactive -> Color(0xFFE26C6C)
        StatusChipState.Reconnecting -> GaugeYellow
    }

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = contentColor,
        modifier = Modifier
            .graphicsLayer(alpha = chipAlpha)
            .padding(
                horizontal = StatusIconHorizontalPadding,
                vertical = StatusIconVerticalPadding,
            )
            .size(StatusIconSize),
    )
}

fun DrawScope.drawCarbonBackground() {
    drawRoundRect(
        brush = CarbonBgBrush,
        cornerRadius = CornerRadius(24f, 24f),
    )

    val stripe = size.width / 18f
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(
            color = CarbonStripeLight,
            start = Offset(x, 0f),
            end = Offset(x + size.height, size.height),
            strokeWidth = size.width / 36f,
        )
        drawLine(
            color = CarbonStripeShadow,
            start = Offset(x + stripe / 2f, 0f),
            end = Offset(x + size.height + stripe / 2f, size.height),
            strokeWidth = size.width / 36f,
        )
        x += stripe
    }
}

private fun textPaint(
    color: Color,
    sizePx: Float,
    align: Paint.Align,
    bold: Boolean,
    skewX: Float = 0f,
): Paint = reusablePaint.apply {
    this.color = color.toArgb()
    textSize = sizePx
    textAlign = align
    isAntiAlias = true
    isSubpixelText = false
    isFakeBoldText = bold
    textSkewX = skewX
}

internal fun DrawScope.drawLabelText(
    text: String,
    position: Offset,
    color: Color,
    sizeSp: Float,
    bold: Boolean,
) {
    val sizePx = sizeSp * min(size.width / GaugeModelWidth, size.height / GaugeModelHeight)
    drawContext.canvas.nativeCanvas.drawText(
        text,
        position.x,
        position.y,
        textPaint(
            color = color,
            sizePx = sizePx,
            align = Paint.Align.CENTER,
            bold = bold,
        ),
    )
}

internal fun DrawScope.drawCenteredText(
    text: String,
    position: Offset,
    color: Color,
    sizeSp: Float,
    bold: Boolean,
) {
    val sizePx = sizeSp * min(size.width / GaugeModelWidth, size.height / GaugeModelHeight)
    val paint = textPaint(
        color = color,
        sizePx = sizePx,
        align = Paint.Align.CENTER,
        bold = bold,
    )
    val baseline = position.y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, position.x, baseline, paint)
}

internal fun DrawScope.drawSkewedCenteredText(
    text: String,
    position: Offset,
    color: Color,
    sizeSp: Float,
    bold: Boolean,
    skewX: Float,
) {
    val sizePx = sizeSp * min(size.width / GaugeModelWidth, size.height / GaugeModelHeight)
    val paint = textPaint(
        color = color,
        sizePx = sizePx,
        align = Paint.Align.CENTER,
        bold = bold,
        skewX = skewX,
    )
    val baseline = position.y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, position.x, baseline, paint)
}

internal fun DrawScope.drawRightAlignedText(
    text: String,
    position: Offset,
    color: Color,
    sizeSp: Float,
    bold: Boolean,
) {
    val sizePx = sizeSp * min(size.width / GaugeModelWidth, size.height / GaugeModelHeight)
    val paint = textPaint(
        color = color,
        sizePx = sizePx,
        align = Paint.Align.RIGHT,
        bold = bold,
    )
    val baseline = position.y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, position.x, baseline, paint)
}

internal fun DrawScope.drawLeftAlignedText(
    text: String,
    position: Offset,
    color: Color,
    sizeSp: Float,
    bold: Boolean,
) {
    val sizePx = sizeSp * min(size.width / GaugeModelWidth, size.height / GaugeModelHeight)
    val paint = textPaint(
        color = color,
        sizePx = sizePx,
        align = Paint.Align.LEFT,
        bold = bold,
    )
    val baseline = position.y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, position.x, baseline, paint)
}

internal fun rollHeatColor(progress: Float): Color = GaugeBlue

internal fun polar(center: Offset, radius: Float, angleDeg: Float): Offset {
    val radians = Math.toRadians(angleDeg.toDouble())
    return Offset(
        x = center.x + (cos(radians) * radius).toFloat(),
        y = center.y + (sin(radians) * radius).toFloat(),
    )
}

internal fun GaugeStudioConfig.previewHeight(): Dp = baseHeight * transform.scale

internal fun GaugeStudioConfig.previewWidth(): Dp = previewHeight() * widthRatio

@Composable
internal fun TelemetryPreviewFrame(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable () -> Unit,
) {
    RideScopeTheme(darkTheme = true) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .drawBehind { drawCarbonBackground() }
                .padding(12.dp),
            contentAlignment = contentAlignment,
        ) {
            content()
        }
    }
}

@Preview(
    name = "Telemetry Dashboard",
    group = "Telemetry",
    showBackground = true,
    backgroundColor = 0xFF090B0F,
    widthDp = 412,
    heightDp = 360,
)
@Composable
private fun TelemetryDashboardWidgetPreview() {
    TelemetryPreviewFrame {
        TelemetryDashboardWidget(
            state = TelemetryPreviewState,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
