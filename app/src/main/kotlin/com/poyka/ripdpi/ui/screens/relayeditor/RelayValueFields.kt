package com.poyka.ripdpi.ui.screens.relayeditor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration

/** The TCP/UDP port range every server and listener port field accepts. */
internal val RelayPortRange: IntRange = 1..65_535

/** Why a field the editor marks as wrong is wrong: an empty field is required, any other value is not valid. */
@Composable
internal fun fieldErrorText(
    value: String,
    hasError: Boolean,
    @StringRes invalidRes: Int = R.string.field_error_invalid,
): String? =
    when {
        !hasError -> null
        value.isBlank() -> stringResource(R.string.config_error_required)
        else -> stringResource(invalidRes)
    }

/** Look and keyboard shared by the value fields; [RelaySecretField] is the secret counterpart. */
internal data class RelayValueField(
    val label: String,
    val errorText: String? = null,
    val helperText: String? = null,
    val placeholder: String? = null,
    val testTag: String? = null,
    val readOnly: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
private fun RelayValueInput(
    field: RelayValueField,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    modifier: Modifier,
) {
    RipDpiTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        decoration =
            RipDpiTextFieldDecoration(
                label = field.label,
                placeholder = field.placeholder,
                helperText = field.helperText,
                errorText = field.errorText,
                testTag = field.testTag,
            ),
        behavior =
            RipDpiTextFieldBehavior(
                enabled = field.enabled,
                readOnly = field.readOnly,
                // Addresses and numbers are typed as-is: no autocorrect and no leading capital.
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = keyboardType,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
            ),
    )
}

/** A host name or IP address. Spaces never belong in one, so a pasted value loses them. */
@Composable
internal fun RelayHostField(
    field: RelayValueField,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RelayValueInput(
        field,
        value,
        { onValueChange(it.filter { char -> !char.isWhitespace() }) },
        KeyboardType.Uri,
        modifier,
    )
}

/** A URL. Spaces never belong in one, so a pasted value loses them. */
@Composable
internal fun RelayUrlField(
    field: RelayValueField,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RelayValueInput(
        field,
        value,
        { onValueChange(it.filter { char -> !char.isWhitespace() }) },
        KeyboardType.Uri,
        modifier,
    )
}

/**
 * A whole number. With a [range] the field shows the bounds, and a value outside them shows the bounds as its error
 * in place of the caller's more general one; without a range the caller owns the check.
 */
@Composable
internal fun RelayNumberField(
    field: RelayValueField,
    value: String,
    onValueChange: (String) -> Unit,
    range: IntRange? = null,
    modifier: Modifier = Modifier,
) {
    val outOfRange =
        range != null && value.isNotBlank() && value.trim().toIntOrNull()?.let { it in range } != true
    val bounds = range?.let { stringResource(R.string.relay_editor_range_helper, it.first, it.last) }
    RelayValueInput(
        field =
            field.copy(
                helperText = field.helperText ?: bounds,
                errorText = bounds.takeIf { outOfRange } ?: field.errorText,
            ),
        value = value,
        onValueChange = { onValueChange(it.trim()) },
        keyboardType = KeyboardType.Number,
        modifier = modifier,
    )
}

/** A server or listener port in [RelayPortRange]. */
@Composable
internal fun RelayPortField(
    field: RelayValueField,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RelayNumberField(field, value, onValueChange, RelayPortRange, modifier)
}
