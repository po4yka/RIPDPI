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

enum class RipDpiCidrFamily { Ipv4, Ipv6 }

data class RipDpiCidrValue(
    val address: String,
    val prefix: Int,
    val family: RipDpiCidrFamily = RipDpiCidrFamily.Ipv4,
) {
    fun isValid(): Boolean =
        when (family) {
            RipDpiCidrFamily.Ipv4 -> parseIpv4(address) && prefix in 0..32
            RipDpiCidrFamily.Ipv6 -> parseIpv6(address) && prefix in 0..128
        }

    private fun parseIpv4(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            val n = p.toIntOrNull()
            n != null && n in 0..255
        }
    }

    private fun parseIpv6(s: String): Boolean {
        if (!s.contains(":")) return false
        if (s.count { it == ':' && s.indexOf("::") != -1 } > 2 &&
            s.indexOf("::") != s.lastIndexOf("::") + 1
        ) {
            return false
        }
        val doubleColonCount = s.windowed(2).count { it == "::" }
        if (doubleColonCount > 1) return false
        return s.matches(Regex("^[0-9a-fA-F:]+$"))
    }
}

/**
 * Composite IPv4/IPv6 CIDR input: a v4/v6 toggle above, address text
 * field + numeric prefix field, both in mono type, with inline
 * validation hint. Emits a [RipDpiCidrValue] tuple.
 *
 * Matches `components-cidr-input.html`.
 */
@Composable
fun RipDpiCidrInput(
    value: RipDpiCidrValue,
    onValueChange: (RipDpiCidrValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "CIDR",
) {
    val colors = RipDpiThemeTokens.colors
    val maxPrefix = if (value.family == RipDpiCidrFamily.Ipv4) 32 else 128
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
        Text(label, style = RipDpiThemeTokens.type.secondaryBody.copy(color = colors.mutedForeground))
        RipDpiSegmentedButton(
            options = listOf("IPv4", "IPv6"),
            selectedIndex = if (value.family == RipDpiCidrFamily.Ipv4) 0 else 1,
            onSelect = { idx ->
                val newFamily = if (idx == 0) RipDpiCidrFamily.Ipv4 else RipDpiCidrFamily.Ipv6
                val newMax = if (newFamily == RipDpiCidrFamily.Ipv4) 32 else 128
                onValueChange(value.copy(family = newFamily, address = "", prefix = value.prefix.coerceIn(0, newMax)))
            },
            modifier = Modifier.fillMaxWidth(),
        )
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            Text("/", style = RipDpiThemeTokens.type.monoValue.copy(color = colors.mutedForeground))
            OutlinedTextField(
                value = TextFieldValue(value.prefix.toString()),
                onValueChange = { tfv ->
                    val n = tfv.text.toIntOrNull() ?: 0
                    onValueChange(value.copy(prefix = n.coerceIn(0, maxPrefix)))
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = RipDpiThemeTokens.type.monoValue,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (!value.isValid()) {
            val hint =
                if (value.family == RipDpiCidrFamily.Ipv4) {
                    "Invalid CIDR — expected dotted-quad/0..32"
                } else {
                    "Invalid CIDR — expected hex-colon/0..128"
                }
            Text(
                hint,
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
            RipDpiCidrInput(
                value = RipDpiCidrValue("2001:db8::", 32, RipDpiCidrFamily.Ipv6),
                onValueChange = {},
                label = "IPv6 example",
            )
        }
    }
}

@Preview(showBackground = true, name = "RipDpiCidrInput (dark)")
@Composable
private fun RipDpiCidrInputPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiCidrInput(value = RipDpiCidrValue("192.168.1.0", 24), onValueChange = {})
            RipDpiCidrInput(
                value = RipDpiCidrValue("2001:db8::", 32, RipDpiCidrFamily.Ipv6),
                onValueChange = {},
            )
        }
    }
}
