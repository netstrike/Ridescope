package com.example.ridescope.ui.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.roundToInt

internal val RideScopeSpeedGaugeStudioConfig = GaugeStudioConfig(
    baseHeight = 176.dp,
    widthRatio = 1.2f,
    layoutTop = 350.dp,
    transform = GaugeTransform(offsetYModel = 24f, scale = 1.72f, textScale = 1.72f),
)

@Composable
internal fun SpeedGauge(
    gpsFixAvailable: Boolean,
    gpsSpeedKmh: Float,
    modifier: Modifier = Modifier,
) {
    val speedValue = if (gpsFixAvailable) gpsSpeedKmh.roundToInt().toString() else "0"
    BoxWithConstraints(modifier = modifier) {
        val minSide: Dp = minOf(maxWidth, maxHeight)
        val valueFontSize = (minSide.value * 0.36f).sp
        val unitFontSize = (minSide.value * 0.14f).sp

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -(maxHeight * 0.05f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(maxHeight * 0.01f),
        ) {
            Text(
                text = speedValue,
                color = GaugeWhite,
                fontWeight = FontWeight.Bold,
                fontSize = valueFontSize,
            )
            Text(
                text = "km/h",
                color = GaugeWhite,
                fontWeight = FontWeight.Normal,
                fontSize = unitFontSize,
            )
        }
    }
}

@Preview(name = "Speed Gauge", group = "Telemetry", showBackground = true, backgroundColor = 0xFF090B0F, widthDp = 412, heightDp = 280)
@Composable
private fun SpeedGaugePreview() {
    TelemetryPreviewFrame {
        SpeedGauge(
            gpsFixAvailable = TelemetryPreviewState.gpsFixAvailable,
            gpsSpeedKmh = TelemetryPreviewState.gpsSpeedKmh.toFloat(),
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 0.dp),
        )
    }
}
