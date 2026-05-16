package com.example.ridescope.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

enum class RideScopePopupType {
    Info,
    Warning,
    Error,
}

private data class RideScopePopupVisuals(
    override val message: String,
    val type: RideScopePopupType,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

@Composable
fun RideScopePopupHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        SnackbarHost(
            hostState = hostState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) { data ->
            val popupType = (data.visuals as? RideScopePopupVisuals)?.type ?: RideScopePopupType.Info
            val shape = RoundedCornerShape(16.dp)
            val containerColor = when (popupType) {
                RideScopePopupType.Info -> Color(0xFF10243A)
                RideScopePopupType.Warning -> Color(0xFFF2B705)
                RideScopePopupType.Error -> Color(0xFF8E1B1B)
            }
            val borderColor = when (popupType) {
                RideScopePopupType.Info -> Color(0xFF5AA9FF)
                RideScopePopupType.Warning -> Color(0xFFFFE082)
                RideScopePopupType.Error -> Color(0xFFFF7B7B)
            }
            val contentColor = when (popupType) {
                RideScopePopupType.Warning -> Color(0xFF111111)
                RideScopePopupType.Info,
                RideScopePopupType.Error,
                -> Color.White
            }
            Snackbar(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = borderColor, shape = shape),
                shape = shape,
                containerColor = containerColor,
                contentColor = contentColor,
            ) {
                Text(data.visuals.message, color = contentColor)
            }
        }
    }
}

suspend fun SnackbarHostState.showRideScopePopup(
    message: String,
    type: RideScopePopupType = RideScopePopupType.Info,
) {
    currentSnackbarData?.dismiss()
    showSnackbar(
        visuals = RideScopePopupVisuals(
            message = message,
            type = type,
        ),
    )
}
