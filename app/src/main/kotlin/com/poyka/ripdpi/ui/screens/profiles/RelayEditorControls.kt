package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
) {
    RipDpiTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        decoration = RipDpiTextFieldDecoration(label = label, errorText = errorText, testTag = testTag),
        behavior =
            RipDpiTextFieldBehavior(
                textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
            ),
        trailingContent = {
            RipDpiButton(
                text = stringResource(R.string.ssh_reveal_secret_action),
                onClick = onReveal,
                variant = RipDpiButtonVariant.Secondary,
                leadingIcon = RipDpiIcons.Visibility,
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
) {
    var revealed by remember { mutableStateOf(false) }
    RelaySecretField(
        label = label,
        value = value,
        revealed = revealed,
        onValueChange = onValueChange,
        onReveal = { revealed = true },
        errorText = errorText,
    )
}

/** The closing action row of every profile editor; [hint] says why the action is not available yet. */
@Composable
internal fun RelayEditorActionRow(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    hint: String?,
    modifier: Modifier = Modifier,
    hintIsError: Boolean = false,
    leadingIcon: ImageVector? = null,
    testTag: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        hint?.let {
            Text(
                text = it,
                style = RipDpiThemeTokens.type.caption,
                color = if (hintIsError) colors.destructive else colors.mutedForeground,
            )
        }
        RipDpiButton(
            text = text,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(testTag),
            enabled = enabled,
            leadingIcon = leadingIcon,
        )
    }
}
