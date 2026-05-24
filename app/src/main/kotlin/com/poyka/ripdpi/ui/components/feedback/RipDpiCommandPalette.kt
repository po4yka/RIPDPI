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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private val commandPaletteItemGap = 2.dp
private val commandPaletteIconSize = 16.dp

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
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val matches = remember(query.text, commands) { matchingCommands(query.text, commands) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Esc is handled by Dialog's onDismissRequest — Compose maps the hardware Esc key to onDismissRequest in Dialog.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CommandPaletteContent(
            query = query,
            matches = matches,
            focusRequester = focusRequester,
            onQueryChange = { query = it },
            onDismiss = onDismiss,
            onCommand = onCommand,
            modifier = modifier,
        )
    }
}

@Composable
private fun CommandPaletteContent(
    query: TextFieldValue,
    matches: List<RipDpiCommand>,
    focusRequester: FocusRequester,
    onQueryChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onCommand: (RipDpiCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
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
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Type a command…", style = RipDpiThemeTokens.type.body) },
                textStyle = RipDpiThemeTokens.type.body,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions =
                    KeyboardActions(
                        onGo = {
                            matches.firstOrNull()?.let {
                                onCommand(it)
                                onDismiss()
                            }
                        },
                    ),
            )
            CommandList(matches = matches, onDismiss = onDismiss, onCommand = onCommand)
        }
    }
}

@Composable
private fun CommandList(
    matches: List<RipDpiCommand>,
    onDismiss: () -> Unit,
    onCommand: (RipDpiCommand) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(commandPaletteItemGap)) {
        items(matches, key = { it.key }) { command ->
            CommandRow(command = command, onDismiss = onDismiss, onCommand = onCommand)
        }
        if (matches.isEmpty()) {
            items(listOf("empty")) {
                Text(
                    "No matching command",
                    modifier = Modifier.padding(RipDpiThemeTokens.spacing.md),
                    style = RipDpiThemeTokens.type.body.copy(color = RipDpiThemeTokens.colors.mutedForeground),
                )
            }
        }
    }
}

@Composable
private fun CommandRow(
    command: RipDpiCommand,
    onDismiss: () -> Unit,
    onCommand: (RipDpiCommand) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiClickable(enabled = true) {
                    onCommand(command)
                    onDismiss()
                }.padding(
                    horizontal = RipDpiThemeTokens.spacing.md,
                    vertical = RipDpiThemeTokens.spacing.sm,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(command.label, style = RipDpiThemeTokens.type.bodyEmphasis)
            command.hint?.let {
                Text(
                    it,
                    style =
                        RipDpiThemeTokens.type.monoSmall
                            .copy(color = colors.mutedForeground),
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = colors.mutedForeground,
            modifier = Modifier.size(commandPaletteIconSize),
        )
    }
}

private fun matchingCommands(
    query: String,
    commands: List<RipDpiCommand>,
): List<RipDpiCommand> =
    if (query.isBlank()) {
        commands
    } else {
        val lowerQuery = query.lowercase()
        commands.filter { command ->
            command.label.lowercase().contains(lowerQuery) || command.hint?.lowercase()?.contains(lowerQuery) == true
        }
    }

@Preview(showBackground = true, name = "RipDpiCommandPalette (light)")
@Composable
private fun RipDpiCommandPaletteLightPreview() {
    RipDpiComponentPreview {
        Text("Command palette is a modal Dialog; see runtime preview.")
    }
}
