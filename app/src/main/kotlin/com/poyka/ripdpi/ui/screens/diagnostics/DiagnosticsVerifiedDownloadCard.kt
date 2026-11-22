package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.VerifiedDownloadInput
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun VerifiedDownloadInputCard(
    input: VerifiedDownloadInput,
    enabled: Boolean,
    onInputChanged: (VerifiedDownloadInput) -> Unit,
) {
    val url = remember { LaggingInputText(input.url) }
    val size = remember { LaggingInputText(input.expectedBodyBytes) }
    val digest = remember { LaggingInputText(input.sha256) }
    SideEffect {
        url.sync(input.url)
        size.sync(input.expectedBodyBytes)
        digest.sync(input.sha256)
    }

    fun currentInput() = VerifiedDownloadInput(url.text, size.text, digest.text)

    val allFieldsFilled = url.text.isNotBlank() && size.text.isNotBlank() && digest.text.isNotBlank()
    val showError = allFieldsFilled && !currentInput().isValid()
    val publishInput = { onInputChanged(currentInput()) }
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        VerifiedDownloadField(
            R.string.diagnostics_verified_download_url,
            url,
            enabled,
            KeyboardType.Uri,
            onEdited = publishInput,
        )
        VerifiedDownloadField(
            R.string.diagnostics_verified_download_bytes,
            size,
            enabled,
            KeyboardType.Number,
            onEdited = publishInput,
        )
        VerifiedDownloadField(
            R.string.diagnostics_verified_download_sha256,
            digest,
            enabled,
            KeyboardType.Ascii,
            onEdited = publishInput,
        )
        if (showError) {
            Text(
                stringResource(R.string.diagnostics_verified_download_input_error),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.destructive,
            )
        }
    }
}

@Composable
private fun VerifiedDownloadField(
    @StringRes label: Int,
    state: LaggingInputText,
    enabled: Boolean,
    keyboardType: KeyboardType,
    onEdited: () -> Unit,
) {
    RipDpiTextField(
        value = state.text,
        onValueChange = { value ->
            state.edit(value.trim())
            onEdited()
        },
        modifier = Modifier.fillMaxWidth(),
        decoration = RipDpiTextFieldDecoration(label = stringResource(label)),
        behavior =
            RipDpiTextFieldBehavior(
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            ),
    )
}
