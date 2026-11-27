package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.TargetClassCdn
import com.poyka.ripdpi.diagnostics.TargetClassDomesticHosting
import com.poyka.ripdpi.diagnostics.TargetClassForeignDatacenter
import com.poyka.ripdpi.diagnostics.TransferVolumeComparisonInput
import com.poyka.ripdpi.diagnostics.VerifiedDownloadInput
import com.poyka.ripdpi.diagnostics.VerifiedTransferComparisonInput
import com.poyka.ripdpi.diagnostics.VerifiedUploadInput
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
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
    val fields = rememberVerifiedDownloadFields(input)
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        VerifiedDownloadFields(fields, enabled) { onInputChanged(fields.input()) }
        if (fields.isFilled() && !fields.input().isValid()) {
            VerifiedTransferInputError(R.string.diagnostics_verified_download_input_error)
        }
    }
}

@Composable
internal fun TransferVolumeComparisonInputCard(
    input: TransferVolumeComparisonInput,
    enabled: Boolean,
    onInputChanged: (TransferVolumeComparisonInput) -> Unit,
) {
    val address = remember { LaggingInputText(input.connectIp) }
    SideEffect { address.sync(input.connectIp) }
    val short = rememberVerifiedDownloadFields(input.short)
    val long = rememberVerifiedDownloadFields(input.long)
    val asn = remember { LaggingInputText(input.destinationAsn) }
    SideEffect { asn.sync(input.destinationAsn) }

    fun currentInput() =
        TransferVolumeComparisonInput(address.text, short.input(), long.input(), input.targetClass, asn.text)
    val publishInput = { onInputChanged(currentInput()) }

    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        VerifiedTransferField(
            R.string.diagnostics_ech_transfer_ip,
            address,
            enabled,
            KeyboardType.Ascii,
            onEdited = publishInput,
        )
        VolumeTransferHeading(R.string.diagnostics_volume_transfer_short)
        VerifiedDownloadFields(short, enabled, publishInput)
        VolumeTransferHeading(R.string.diagnostics_volume_transfer_long)
        VerifiedDownloadFields(long, enabled, publishInput)
        VolumeTransferHeading(R.string.diagnostics_volume_transfer_class)
        VolumeTargetClassChips(input.targetClass, enabled) { selected ->
            onInputChanged(currentInput().copy(targetClass = selected))
        }
        VerifiedTransferField(
            R.string.diagnostics_volume_transfer_asn,
            asn,
            enabled,
            KeyboardType.Number,
            onEdited = publishInput,
        )
        val isFilled = address.text.isNotBlank() && short.isFilled() && long.isFilled()
        if (isFilled && !currentInput().isValid()) {
            VerifiedTransferInputError(R.string.diagnostics_volume_transfer_input_error)
        }
    }
}

@Composable
private fun VolumeTargetClassChips(
    selected: String?,
    enabled: Boolean,
    onSelected: (String?) -> Unit,
) {
    val options =
        listOf(
            null to R.string.diagnostics_volume_transfer_class_none,
            TargetClassDomesticHosting to R.string.diagnostics_volume_transfer_class_domestic_hosting,
            TargetClassForeignDatacenter to R.string.diagnostics_volume_transfer_class_foreign_datacenter,
            TargetClassCdn to R.string.diagnostics_volume_transfer_class_cdn,
        )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        options.forEach { (value, label) ->
            RipDpiChip(
                text = stringResource(label),
                onClick = { onSelected(value) },
                selected = selected == value,
                enabled = enabled,
                role = Role.RadioButton,
            )
        }
    }
}

@Composable
private fun VolumeTransferHeading(
    @StringRes label: Int,
) {
    Text(
        stringResource(label),
        modifier = Modifier.semantics { heading() },
        style = RipDpiThemeTokens.type.bodyEmphasis,
    )
}

private class VerifiedDownloadFieldState(
    input: VerifiedDownloadInput,
) {
    val url = LaggingInputText(input.url)
    val size = LaggingInputText(input.expectedBodyBytes)
    val digest = LaggingInputText(input.sha256)

    fun sync(input: VerifiedDownloadInput) {
        url.sync(input.url)
        size.sync(input.expectedBodyBytes)
        digest.sync(input.sha256)
    }

    fun isFilled(): Boolean = url.text.isNotBlank() && size.text.isNotBlank() && digest.text.isNotBlank()

    fun input(): VerifiedDownloadInput = VerifiedDownloadInput(url.text, size.text, digest.text)
}

@Composable
private fun rememberVerifiedDownloadFields(input: VerifiedDownloadInput): VerifiedDownloadFieldState {
    val fields = remember { VerifiedDownloadFieldState(input) }
    SideEffect { fields.sync(input) }
    return fields
}

@Composable
private fun VerifiedDownloadFields(
    fields: VerifiedDownloadFieldState,
    enabled: Boolean,
    onEdited: () -> Unit,
) {
    VerifiedTransferField(R.string.diagnostics_verified_download_url, fields.url, enabled, KeyboardType.Uri, onEdited)
    VerifiedTransferField(
        R.string.diagnostics_verified_download_bytes,
        fields.size,
        enabled,
        KeyboardType.Number,
        onEdited,
    )
    VerifiedTransferField(
        R.string.diagnostics_verified_download_sha256,
        fields.digest,
        enabled,
        KeyboardType.Ascii,
        onEdited,
    )
}

@Composable
internal fun VerifiedUploadInputCard(
    input: VerifiedUploadInput,
    enabled: Boolean,
    onInputChanged: (VerifiedUploadInput) -> Unit,
) {
    val url = remember { LaggingInputText(input.url) }
    val size = remember { LaggingInputText(input.bodyBytes) }
    SideEffect {
        url.sync(input.url)
        size.sync(input.bodyBytes)
    }

    fun currentInput() = VerifiedUploadInput(url.text, size.text)

    val showError = url.text.isNotBlank() && size.text.isNotBlank() && !currentInput().isValid()
    val publishInput = { onInputChanged(currentInput()) }
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        VerifiedTransferField(
            R.string.diagnostics_verified_download_url,
            url,
            enabled,
            KeyboardType.Uri,
            onEdited = publishInput,
        )
        VerifiedTransferField(
            R.string.diagnostics_verified_upload_bytes,
            size,
            enabled,
            KeyboardType.Number,
            onEdited = publishInput,
        )
        if (showError) {
            VerifiedTransferInputError(R.string.diagnostics_verified_upload_input_error)
        }
    }
}

@Composable
internal fun VerifiedTransferComparisonInputCard(
    http3: Boolean,
    input: VerifiedTransferComparisonInput,
    enabled: Boolean,
    onInputChanged: (VerifiedTransferComparisonInput) -> Unit,
) {
    val url = remember { LaggingInputText(input.url) }
    val address = remember { LaggingInputText(input.connectIp) }
    val size = remember { LaggingInputText(input.expectedBodyBytes) }
    val digest = remember { LaggingInputText(input.sha256) }
    SideEffect {
        url.sync(input.url)
        address.sync(input.connectIp)
        size.sync(input.expectedBodyBytes)
        digest.sync(input.sha256)
    }

    fun currentInput() = VerifiedTransferComparisonInput(url.text, address.text, size.text, digest.text)

    val allFieldsFilled = listOf(url, address, size, digest).all { it.text.isNotBlank() }
    val publishInput = { onInputChanged(currentInput()) }
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        VerifiedTransferField(
            R.string.diagnostics_verified_download_url,
            url,
            enabled,
            KeyboardType.Uri,
            onEdited = publishInput,
        )
        VerifiedTransferField(
            R.string.diagnostics_ech_transfer_ip,
            address,
            enabled,
            KeyboardType.Ascii,
            onEdited = publishInput,
        )
        VerifiedTransferField(
            R.string.diagnostics_verified_download_bytes,
            size,
            enabled,
            KeyboardType.Number,
            onEdited = publishInput,
        )
        VerifiedTransferField(
            R.string.diagnostics_verified_download_sha256,
            digest,
            enabled,
            KeyboardType.Ascii,
            onEdited = publishInput,
        )
        if (allFieldsFilled) {
            val input = currentInput()
            when {
                http3 && !input.isValidForHttp3() -> {
                    VerifiedTransferInputError(R.string.diagnostics_http3_transfer_input_error)
                }

                !http3 && !input.isValidForEch() -> {
                    VerifiedTransferInputError(R.string.diagnostics_ech_transfer_input_error)
                }
            }
        }
    }
}

@Composable
private fun VerifiedTransferInputError(
    @StringRes message: Int,
) {
    Text(
        stringResource(message),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.destructive,
    )
}

@Composable
private fun VerifiedTransferField(
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
