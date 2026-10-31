package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val MinPort = 1
private const val MaxPort = 65_535

/** Helper text for every server-port field. */
@Composable
internal fun relayPortRangeHelper(): String = stringResource(R.string.relay_editor_range_helper, MinPort, MaxPort)

/** The one secret field of every profile editor: masked until the user taps Reveal. */
@Composable
internal fun RelaySecretField(
    label: String,
    value: String,
    revealed: Boolean,
    onValueChange: (String) -> Unit,
    onReveal: () -> Unit,
    errorText: String? = null,
    testTag: String? = null,
    enabled: Boolean = true,
) {
    RipDpiTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        decoration = RipDpiTextFieldDecoration(label = label, errorText = errorText, testTag = testTag),
        behavior =
            RipDpiTextFieldBehavior(
                textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                enabled = enabled,
            ),
        trailingContent = {
            RipDpiIconButton(
                icon = RipDpiIcons.Visibility,
                contentDescription = stringResource(R.string.ssh_reveal_secret_action),
                onClick = onReveal,
                modifier = Modifier.ripDpiTestTag(testTag?.let(RipDpiTestTags.secretReveal)),
                enabled = enabled,
            )
        },
    )
}

/** A typed password: the same Reveal control, with the reveal kept only while the editor is open. */
@Composable
internal fun RelayPasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    errorText: String? = null,
    testTag: String? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    RelaySecretField(
        label = label,
        value = value,
        revealed = revealed,
        onValueChange = onValueChange,
        onReveal = { revealed = true },
        errorText = errorText,
        testTag = testTag,
    )
}

/** The caption that explains why the editor's primary action in the top app bar is disabled. */
@Composable
internal fun RelayEditorHint(
    hint: String?,
    isError: Boolean = false,
) {
    hint?.let {
        Text(
            text = it,
            style = RipDpiThemeTokens.type.caption,
            color = if (isError) RipDpiThemeTokens.colors.destructive else RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}
