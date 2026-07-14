package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Free-text input with a dropdown of suggested completions. Typing
 * filters [suggestions] by substring; tapping a suggestion replaces
 * the text and closes the menu. Free-text values that don't match
 * any suggestion are still accepted.
 *
 * Matches `components-combobox.html`.
 */
@Composable
fun RipDpiCombobox(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: ImmutableList<String>,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val matches =
        remember(value, suggestions) {
            if (value.isBlank()) suggestions else suggestions.filter { it.contains(value, ignoreCase = true) }
        }
    Box(modifier = modifier.fillMaxWidth()) {
        RipDpiTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = it.isNotEmpty()
            },
            modifier = Modifier.fillMaxWidth(),
            decoration = RipDpiTextFieldDecoration(label = label),
            behavior = RipDpiTextFieldBehavior(singleLine = true),
        )
        DropdownMenu(
            expanded = expanded && matches.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RipDpiThemeTokens.colors.card, RipDpiThemeTokens.shapes.sm),
        ) {
            matches.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion, style = RipDpiThemeTokens.type.body) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiCombobox (light)")
@Composable
private fun RipDpiComboboxLightPreview() {
    RipDpiComponentPreview {
        var v by remember { mutableStateOf("") }
        Column(
            modifier = Modifier.padding(RipDpiThemeTokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            RipDpiCombobox(
                value = v,
                onValueChange = { v = it },
                suggestions = persistentListOf("relay.example.com", "relay-eu.example.com", "relay-us.example.com"),
                label = "Relay host",
            )
        }
    }
}

@Preview(showBackground = true, name = "RipDpiCombobox (dark)")
@Composable
private fun RipDpiComboboxDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiCombobox(
            value = "tls",
            onValueChange = {},
            suggestions = persistentListOf("tlsrec", "tlsrec_split_host", "fake_tls"),
        )
    }
}
