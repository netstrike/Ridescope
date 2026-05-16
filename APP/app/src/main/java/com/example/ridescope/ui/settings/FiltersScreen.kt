package com.example.ridescope.ui.settings

import android.text.InputFilter
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.draw.scale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.widget.doAfterTextChanged
import com.example.ridescope.data.model.FirmwareConfig
import com.example.ridescope.data.repository.TelemetryRepository
import com.example.ridescope.ui.common.RideScopeCardTitle
import com.example.ridescope.ui.common.RideScopePageTitle
import com.example.ridescope.ui.common.RideScopePopupHost
import com.example.ridescope.ui.common.RideScopePopupType
import com.example.ridescope.ui.common.showRideScopePopup
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun FiltersScreen(
    repository: TelemetryRepository,
    paddingValues: PaddingValues,
    pageMenu: (@Composable () -> Unit)? = null,
    pageHomeButton: (@Composable () -> Unit)? = null,
) {
    var config by remember { mutableStateOf(FirmwareConfig()) }
    var savedConfig by remember { mutableStateOf<FirmwareConfig?>(null) }
    var activeFieldHelp by remember { mutableStateOf<FilterFieldHelp?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        runCatching { repository.readConfig() }
            .onSuccess {
                config = it
                savedConfig = it
            }
            .onFailure {
                snackbarHostState.showRideScopePopup(
                    message = "Errore lettura filtri: ${it.message}",
                    type = RideScopePopupType.Error,
                )
            }
    }

    LaunchedEffect(config, savedConfig, snackbarHostState) {
        val lastSavedConfig = savedConfig ?: return@LaunchedEffect
        if (config == lastSavedConfig) {
            return@LaunchedEffect
        }

        delay(250)
        runCatching { repository.writeConfig(config, lastSavedConfig) }
            .onSuccess { updatedConfig ->
                config = updatedConfig
                savedConfig = updatedConfig
            }
            .onFailure {
                snackbarHostState.showRideScopePopup(
                    message = "Errore salvataggio filtri: ${it.message}",
                    type = RideScopePopupType.Error,
                )
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(top = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RideScopePageTitle(
                title = "Filtri",
                menuButton = pageMenu,
                trailingAction = pageHomeButton,
            )

            SectionCard(title = "Parametri") {
                FilterParameterRow(
                    checked = config.complementaryEnabled,
                    onCheckedChange = { config = config.copy(complementaryEnabled = it) },
                    help = complementaryHelp,
                    onHelpClick = { activeFieldHelp = complementaryHelp },
                ) {
                    AutoCommitDecimalField(
                        value = config.complementaryAlpha,
                        label = "Complementare",
                        invalidMessage = "Inserisci un valore tra 0 e 1",
                        validator = { it in 0.0..1.0 },
                        enabled = config.complementaryEnabled,
                    ) {
                        config = config.copy(complementaryAlpha = it)
                    }
                }
                FilterParameterRow(
                    checked = config.accelLpfEnabled,
                    onCheckedChange = { config = config.copy(accelLpfEnabled = it) },
                    help = accelLpfHelp,
                    onHelpClick = { activeFieldHelp = accelLpfHelp },
                ) {
                    AutoCommitDecimalField(
                        value = config.accelLpfAlpha,
                        label = "Passa Basso Accelerometri",
                        invalidMessage = "Inserisci un valore tra 0 e 1",
                        validator = { it in 0.0..1.0 },
                        enabled = config.accelLpfEnabled,
                    ) {
                        config = config.copy(accelLpfAlpha = it)
                    }
                }
                FilterParameterRow(
                    checked = config.gyroLpfEnabled,
                    onCheckedChange = { config = config.copy(gyroLpfEnabled = it) },
                    help = gyroLpfHelp,
                    onHelpClick = { activeFieldHelp = gyroLpfHelp },
                ) {
                    AutoCommitDecimalField(
                        value = config.gyroLpfAlpha,
                        label = "Passa Basso Giroscopi",
                        invalidMessage = "Inserisci un valore tra 0 e 1",
                        validator = { it in 0.0..1.0 },
                        enabled = config.gyroLpfEnabled,
                    ) {
                        config = config.copy(gyroLpfAlpha = it)
                    }
                }
                FilterParameterRow(
                    checked = config.adaptiveAccelTrustEnabled,
                    onCheckedChange = { config = config.copy(adaptiveAccelTrustEnabled = it) },
                    help = accelTrustSpanHelp,
                    onHelpClick = { activeFieldHelp = accelTrustSpanHelp },
                ) {
                    AutoCommitDecimalField(
                        value = config.accelTrustFadeSpanG,
                        label = "Fiducia Accelerometri (span)",
                        invalidMessage = "Inserisci un valore >= 0",
                        validator = { it >= 0.0 },
                        enabled = config.adaptiveAccelTrustEnabled,
                    ) {
                        config = config.copy(accelTrustFadeSpanG = it)
                    }
                }
                FilterParameterRow(
                    showSwitch = false,
                    help = accelTrustMinHelp,
                    onHelpClick = { activeFieldHelp = accelTrustMinHelp },
                ) {
                    AutoCommitDecimalField(
                        value = config.accelTrustMinG,
                        label = "Fiducia Accelerometri (min)",
                        invalidMessage = "Inserisci un valore >= 0",
                        validator = { it >= 0.0 },
                        enabled = config.adaptiveAccelTrustEnabled,
                    ) {
                        config = config.copy(accelTrustMinG = it)
                    }
                }
                FilterParameterRow(
                    showSwitch = false,
                    help = accelTrustMaxHelp,
                    onHelpClick = { activeFieldHelp = accelTrustMaxHelp },
                ) {
                    AutoCommitDecimalField(
                        value = config.accelTrustMaxG,
                        label = "Fiducia Accelerometri (max)",
                        invalidMessage = "Inserisci un valore >= accel_trust_min_g",
                        validator = { it >= config.accelTrustMinG },
                        enabled = config.adaptiveAccelTrustEnabled,
                    ) {
                        config = config.copy(accelTrustMaxG = it)
                    }
                }
            }
        }

        RideScopePopupHost(hostState = snackbarHostState)
        activeFieldHelp?.let { help ->
            FilterFieldHelpDialog(
                help = help,
                onDismiss = { activeFieldHelp = null },
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RideScopeCardTitle(title = title)
            content()
        }
    }
}

@Composable
private fun AutoCommitDecimalField(
    value: Double,
    label: String,
    invalidMessage: String,
    validator: (Double) -> Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueCommitted: (Double) -> Unit,
) {
    AutoCommitTextField(
        value = formatDecimal(value),
        label = label,
        invalidMessage = invalidMessage,
        validator = { text ->
            parseDecimal(text)?.let(validator) == true
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        enabled = enabled,
        modifier = modifier,
    ) { text ->
        parseDecimal(text)?.let(onValueCommitted)
    }
}

@Composable
private fun AutoCommitTextField(
    value: String,
    label: String,
    invalidMessage: String,
    validator: (String) -> Boolean,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueCommitted: (String) -> Unit,
) {
    var text by remember(value, enabled) { mutableStateOf(value) }
    val isDirty = enabled && text != value
    val isValid = validator(text)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    if (keyboardOptions.keyboardType == KeyboardType.Decimal) {
        NativeDecimalTextField(
            text = text,
            label = label,
            invalidMessage = invalidMessage,
            isDirty = isDirty,
            isValid = isValid,
            enabled = enabled,
            modifier = modifier,
            onTextChange = { updated ->
                if (enabled) {
                    text = updated
                }
            },
            onCommit = {
                if (enabled && isDirty && isValid) {
                    onValueCommitted(text)
                }
                keyboardController?.hide()
                focusManager.clearFocus()
            },
        )
        return
    }

    fun commitAndCloseKeyboard() {
        if (enabled && isDirty && isValid) {
            onValueCommitted(text)
        }
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(text, value, enabled) {
        if (!enabled || !isDirty || !isValid) {
            return@LaunchedEffect
        }

        delay(400)
        if (text != value && validator(text)) {
            onValueCommitted(text)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            if (enabled) {
                text = updated
            }
        },
        label = { Text(label) },
        enabled = enabled,
        isError = isDirty && !isValid,
        supportingText = if (isDirty && !isValid) {
            { Text(invalidMessage) }
        } else {
            null
        },
        keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = { commitAndCloseKeyboard() },
        ),
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun NativeDecimalTextField(
    text: String,
    label: String,
    invalidMessage: String,
    isDirty: Boolean,
    isValid: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onTextChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentEnabled by rememberUpdatedState(enabled)
    val textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge)
    val borderShape = RoundedCornerShape(16.dp)
    val borderColor = if (isDirty && !isValid) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.outline
    }
    val fieldBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = if (enabled) 0.16f else 0.08f,
    )
    val labelBackgroundColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (enabled) 1f else 0.38f,
    ).toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isDirty && !isValid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(1f)
                    .offset(x = 16.dp, y = (-8).dp)
                    .background(color = labelBackgroundColor, shape = RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .border(width = 1.dp, color = borderColor, shape = borderShape)
                    .background(color = fieldBackgroundColor, shape = borderShape)
                    .padding(start = 20.dp, end = 6.dp, top = 0.dp, bottom = 0.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                AndroidView(
                    factory = { context ->
                        EditText(context).apply {
                            background = null
                            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            keyListener = DigitsKeyListener.getInstance("0123456789.")
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            maxLines = 1
                            setSingleLine(true)
                            setHorizontallyScrolling(true)
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                            setPadding(0, 0, 0, 0)
                            setTextColor(textColor)
                            setHintTextColor(hintColor)
                            textSize = textStyle.fontSize.value
                            filters = arrayOf(
                                InputFilter { source, start, end, dest, dstart, dend ->
                                    val builder = StringBuilder()
                                    val destination = dest?.toString().orEmpty()
                                    val safeDstart = dstart.coerceIn(0, destination.length)
                                    val safeDend = dend.coerceIn(safeDstart, destination.length)
                                    val candidateBefore = destination.removeRange(safeDstart, safeDend)
                                    var hasDot = candidateBefore.contains('.')
                                    for (index in start until end) {
                                        val char = source[index]
                                        when {
                                            char.isDigit() -> builder.append(char)
                                            char == '.' && !hasDot -> {
                                                builder.append(char)
                                                hasDot = true
                                            }
                                        }
                                    }
                                    if (builder.length == end - start) null else builder.toString()
                                },
                            )
                            doAfterTextChanged { editable ->
                                if (currentEnabled) {
                                    currentOnTextChange(editable?.toString().orEmpty())
                                }
                            }
                            setOnEditorActionListener { view, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                    view.clearFocus()
                                    currentOnCommit()
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    },
                    update = { editText ->
                        editText.hint = null
                        editText.isEnabled = enabled
                        editText.setTextColor(textColor)
                        editText.setHintTextColor(hintColor)
                        if (editText.text?.toString() != text) {
                            editText.setText(text)
                            editText.setSelection(text.length)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterStart),
                )
            }
        }
        if (isDirty && !isValid) {
            Text(
                text = invalidMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun FilterParameterRow(
    checked: Boolean = false,
    showSwitch: Boolean = true,
    help: FilterFieldHelp? = null,
    onCheckedChange: (Boolean) -> Unit = {},
    onHelpClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val infoSlotWidth = 44.dp
    val switchSlotWidth = 92.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.weight(1f),
        ) {
            content()
        }
        Box(
            modifier = Modifier
                .width(infoSlotWidth)
                .padding(top = 6.dp)
                .height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (help != null && onHelpClick != null) {
                IconButton(
                    onClick = onHelpClick,
                    modifier = Modifier.scale(0.86f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Info ${help.title}",
                        tint = FilterInfoIconColor,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .width(switchSlotWidth)
                .padding(top = 6.dp)
                .height(48.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (showSwitch) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .scale(0.84f),
                )
            }
        }
    }
}

@Composable
private fun FilterFieldHelpDialog(
    help: FilterFieldHelp,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Text(
                text = help.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.Black,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = help.functionDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black,
                )
                Text(
                    text = "Limiti: ${help.limitsDescription}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "CHIUDI",
                    color = Color.Black,
                )
            }
        },
    )
}

private data class FilterFieldHelp(
    val title: String,
    val functionDescription: String,
    val limitsDescription: String,
)

private val FilterInfoIconColor = Color(0xFF4FC3F7)

private val complementaryHelp = FilterFieldHelp(
    title = "Complementare",
    functionDescription = "Regola il peso base del giroscopio nella fusione tra gyro e accelerometri. Valori alti privilegiano il gyro e rendono la stima piu reattiva ma meno corretta dall accelerometro; valori bassi aumentano la correzione assoluta dagli accelerometri.",
    limitsDescription = "da 0 a 1. 0 = correzione massima dagli accelerometri, 1 = peso massimo al gyro.",
)

private val accelLpfHelp = FilterFieldHelp(
    title = "Passa Basso Accelerometri",
    functionDescription = "Prefiltro sugli accelerometri usato per ridurre rumore e vibrazioni ad alta frequenza prima del calcolo di roll e pitch.",
    limitsDescription = "da 0 a 1. Valori bassi filtrano di piu ma rispondono piu lentamente; valori alti seguono piu rapidamente il segnale.",
)

private val gyroLpfHelp = FilterFieldHelp(
    title = "Passa Basso Giroscopi",
    functionDescription = "Prefiltro leggero sui giroscopi usato per stabilizzare l integrazione senza perdere troppa dinamica.",
    limitsDescription = "da 0 a 1. Valori bassi filtrano di piu ma smorzano la dinamica; valori alti lasciano passare piu movimento.",
)

private val accelTrustSpanHelp = FilterFieldHelp(
    title = "Fiducia Accelerometri (span)",
    functionDescription = "Definisce quanto rapidamente la fiducia negli accelerometri cala fuori dalla finestra compresa tra min e max del modulo accelerometrico. Piu grande e lo span, piu graduale e la discesa della fiducia.",
    limitsDescription = "maggiore o uguale a 0 g. Se viene impostato a 0, nel firmware e usato il fallback interno di 0.25 g.",
)

private val accelTrustMinHelp = FilterFieldHelp(
    title = "Fiducia Accelerometri (min)",
    functionDescription = "Soglia inferiore del modulo accelerometrico considerato affidabile. Se il modulo totale scende sotto questo valore, la fiducia negli accelerometri inizia a diminuire.",
    limitsDescription = "maggiore o uguale a 0 g.",
)

private val accelTrustMaxHelp = FilterFieldHelp(
    title = "Fiducia Accelerometri (max)",
    functionDescription = "Soglia superiore del modulo accelerometrico considerato affidabile. Se il modulo totale supera questo valore, la fiducia negli accelerometri inizia a diminuire.",
    limitsDescription = "maggiore o uguale a 0 g e non inferiore a Fiducia Accelerometri (min).",
)

private fun formatDecimal(value: Double): String {
    return String.format(Locale.US, "%.3f", value)
        .trimEnd('0')
        .trimEnd('.')
        .ifBlank { "0" }
}

private fun parseDecimal(text: String): Double? {
    return text.trim().replace(',', '.').toDoubleOrNull()
}
