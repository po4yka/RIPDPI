package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

data class RipDpiCommand(
    val key: String,
    val label: String,
    val hint: String? = null,
)

/**
 * Modal command palette: top-anchored Dialog with a fuzzy-filtered
 * list of [RipDpiCommand]s. Filtering is substring-insensitive on
 * label and hint. Esc closes; Enter triggers the first match.
 *
 * Matches `components-command-palette.html`.
 */
@Composable
fun RipDpiCommandPalette(
    visible: Boolean,
    commands: List<RipDpiCommand>,
    onDismiss: () -> Unit,
    onCommand: (RipDpiCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val colors = RipDpiThemeTokens.colors
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val matches =
        remember(query.text, commands) {
            if (query.text.isBlank()) {
                commands
            } else {
                val q = query.text.lowercase()
                commands.filter { it.label.lowercase().contains(q) || it.hint?.lowercase()?.contains(q) == true }
            }
        }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(RipDpiThemeTokens.spacing.lg),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .background(colors.card, RoundedCornerShape(RipDpiThemeTokens.spacing.md))
                        .padding(RipDpiThemeTokens.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Type a command…", style = RipDpiThemeTokens.type.body) },
                    textStyle = RipDpiThemeTokens.type.body,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(matches) { cmd ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .ripDpiClickable(enabled = true) {
                                        onCommand(cmd)
                                        onDismiss()
                                    }.padding(
                                        horizontal = RipDpiThemeTokens.spacing.md,
                                        vertical = RipDpiThemeTokens.spacing.sm,
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(cmd.label, style = RipDpiThemeTokens.type.bodyEmphasis)
                                cmd.hint?.let {
                                    Text(
                                        it,
                                        style =
                                            RipDpiThemeTokens.type.monoSmall
                                                .copy(color = colors.mutedForeground),
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Outlined.ArrowForward,
                                contentDescription = null,
                                tint = colors.mutedForeground,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (matches.isEmpty()) {
                        items(listOf("empty")) {
                            Text(
                                "No matching command",
                                modifier = Modifier.padding(RipDpiThemeTokens.spacing.md),
                                style = RipDpiThemeTokens.type.body.copy(color = colors.mutedForeground),
                            )
                        }
                    }
                }
            }
        }
    }
    // suppress unused-icon warning
    RipDpiIcons.Close.run {}
}

@Preview(showBackground = true, name = "RipDpiCommandPalette (light)")
@Composable
private fun RipDpiCommandPalettePreviewLight() {
    RipDpiComponentPreview {
        Text("Command palette is a modal Dialog; see runtime preview.")
    }
}
