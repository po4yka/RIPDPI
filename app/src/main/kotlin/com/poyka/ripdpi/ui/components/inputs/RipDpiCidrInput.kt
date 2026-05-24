package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

data class RipDpiCidrValue(
    val address: String,
    val prefix: Int,
) {
    fun isValid(): Boolean = parseAddress(address) && prefix in 0..32

    private fun parseAddress(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            val n = p.toIntOrNull()
            n != null && n in 0..255
        }
    }
}

/**
 * Composite IPv4 CIDR input: address text field + numeric prefix
 * field, both in mono type, with inline validation hint. Emits a
 * [RipDpiCidrValue] tuple. IPv6 deferred to a follow-up.
 *
 * Matches `components-cidr-input.html` (IPv4 path).
 */
@Composable
fun RipDpiCidrInput(
    value: RipDpiCidrValue,
    onValueChange: (RipDpiCidrValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "CIDR",
) {
    val colors = RipDpiThemeTokens.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
        Text(label, style = RipDpiThemeTokens.type.secondaryBody.copy(color = colors.mutedForeground))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            OutlinedTextField(
                value = TextFieldValue(value.address),
                onValueChange = { tfv -> onValueChange(value.copy(address = tfv.text)) },
                modifier = Modifier.weight(2f),
                singleLine = true,
                textStyle = RipDpiThemeTokens.type.monoValue,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("/", style = RipDpiThemeTokens.type.monoValue.copy(color = colors.mutedForeground))
            OutlinedTextField(
                value = TextFieldValue(value.prefix.toString()),
                onValueChange = { tfv ->
                    val n = tfv.text.toIntOrNull() ?: 0
                    onValueChange(value.copy(prefix = n.coerceIn(0, 32)))
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = RipDpiThemeTokens.type.monoValue,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (!value.isValid()) {
            Text(
                "Invalid CIDR — expected dotted-quad/0..32",
                style = RipDpiThemeTokens.type.caption.copy(color = colors.destructive),
            )
        }
    }
}

@Preview(showBackground = true, name = "RipDpiCidrInput (light)")
@Composable
private fun RipDpiCidrInputPreviewLight() {
    RipDpiComponentPreview {
        var v by remember { mutableStateOf(RipDpiCidrValue("10.0.0.0", 8)) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiCidrInput(value = v, onValueChange = { v = it }, label = "Bypass subnet")
            RipDpiCidrInput(value = RipDpiCidrValue("999.0.0.0", 8), onValueChange = {}, label = "Invalid example")
        }
    }
}

@Preview(showBackground = true, name = "RipDpiCidrInput (dark)")
@Composable
private fun RipDpiCidrInputPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiCidrInput(value = RipDpiCidrValue("192.168.1.0", 24), onValueChange = {})
    }
}
