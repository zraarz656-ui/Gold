package com.tradequest.app.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp

/**
 * The explicit colour set for every text input in the app. `OutlinedTextFieldDefaults`
 * defaults follow the Material theme, which left the text, label and placeholder nearly
 * invisible on our dark panels; these colours come from the theme tokens instead.
 */
@Composable
fun tradeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = tradeColors.onSurface,
    unfocusedTextColor = tradeColors.onSurface,
    disabledTextColor = tradeColors.onSurfaceVariant,
    cursorColor = tradeColors.accent,
    focusedBorderColor = tradeColors.accent,
    unfocusedBorderColor = tradeColors.outline,
    disabledBorderColor = tradeColors.outline,
    focusedLabelColor = tradeColors.accent,
    unfocusedLabelColor = tradeColors.onSurfaceVariant,
    disabledLabelColor = tradeColors.onSurfaceVariant,
    focusedPlaceholderColor = tradeColors.onSurfaceVariant,
    unfocusedPlaceholderColor = tradeColors.onSurfaceVariant,
    focusedContainerColor = tradeColors.surfaceVariant,
    unfocusedContainerColor = tradeColors.surfaceVariant,
    disabledContainerColor = tradeColors.surfaceVariant,
    errorContainerColor = tradeColors.surfaceVariant,
    focusedSupportingTextColor = tradeColors.onSurfaceVariant,
    unfocusedSupportingTextColor = tradeColors.onSurfaceVariant,
)

/**
 * A single-line decimal input with readable colours in every theme. Input text, label and
 * placeholder all use theme tokens; the text style is pinned to on-surface so it stays
 * legible even if a parent changes the default content colour.
 */
@Composable
fun TradeNumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelSize: Int = 11,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = labelSize.sp) },
        singleLine = true,
        colors = tradeFieldColors(),
        textStyle = LocalTextStyle.current.copy(color = tradeColors.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}
