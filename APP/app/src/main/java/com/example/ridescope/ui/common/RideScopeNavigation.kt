package com.example.ridescope.ui.common

import androidx.compose.foundation.border
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val UpdateAvailableMenuColor = Color(0xFFFFE082)
private val MenuDropdownBackground = Color(0xFF1E1E1E)
private val MenuDropdownBorder = Color(0xFF2A2A2A)
private val HeaderIconButtonSize = 38.dp
private val HeaderIconSize = 22.dp
private val HeaderMenuOffsetX = 8.dp
private val HeaderHomeOffsetX = (-8).dp

enum class RideScopePage(
    val title: String,
    val icon: ImageVector,
) {
    Telemetria("Telemetria", Icons.Outlined.Home),
    Registrazioni("Registrazioni", Icons.Outlined.RadioButtonChecked),
    Impostazioni("Impostazioni", Icons.Outlined.Settings),
    Filtri("Filtri", Icons.Outlined.FilterAlt),
    Calibrazione("Calibrazione", Icons.Outlined.Build),
    Aggiornamento("Aggiornamento", Icons.Outlined.Update),
    Diagnostica("Diagnostica", Icons.Outlined.Info),
}

data class RideScopeMenuAction(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val color: Color = Color.White,
)

@Composable
fun RideScopePageTitle(
    title: String,
    modifier: Modifier = Modifier,
    menuButton: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (menuButton != null) {
                Box(modifier = Modifier.offset(x = HeaderMenuOffsetX)) {
                    menuButton()
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = HeaderHomeOffsetX),
        ) {
            trailingAction?.invoke()
        }
    }
}

@Composable
fun RideScopePageHomeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(HeaderIconButtonSize),
    ) {
        Icon(
            imageVector = Icons.Outlined.Home,
            contentDescription = "Telemetria",
            tint = contentColor,
            modifier = Modifier.size(HeaderIconSize),
        )
    }
}

@Composable
fun RideScopePageMenuButton(
    selectedPage: RideScopePage,
    onSelectPage: (RideScopePage) -> Unit,
    updateAvailable: Boolean,
    extraActions: List<RideScopeMenuAction> = emptyList(),
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
) {
    var expanded by remember { mutableStateOf(false) }
    val menuIconTint = if (updateAvailable && selectedPage != RideScopePage.Aggiornamento) {
        UpdateAvailableMenuColor
    } else {
        contentColor
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(HeaderIconButtonSize),
        ) {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = "Menu",
                tint = menuIconTint,
                modifier = Modifier.size(HeaderIconSize),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.border(1.dp, MenuDropdownBorder, RoundedCornerShape(12.dp)),
            containerColor = MenuDropdownBackground,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            RideScopePage.entries.forEach { page ->
                val itemColor = if (page == RideScopePage.Aggiornamento && updateAvailable) {
                    UpdateAvailableMenuColor
                } else {
                    Color.White
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = page.title,
                            fontWeight = if (page == selectedPage) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = page.icon,
                            contentDescription = page.title,
                        )
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = itemColor,
                        leadingIconColor = itemColor,
                    ),
                    onClick = {
                        expanded = false
                        onSelectPage(page)
                    },
                )
            }
            if (extraActions.isNotEmpty()) {
                HorizontalDivider(color = MenuDropdownBorder)
                extraActions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(text = action.title) },
                        leadingIcon = {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = action.title,
                            )
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = action.color,
                            leadingIconColor = action.color,
                        ),
                        onClick = {
                            expanded = false
                            action.onClick()
                        },
                    )
                }
            }
        }
    }
}
