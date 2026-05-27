package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigPresetKind
import com.poyka.ripdpi.activities.ConfigUiState

@Composable
internal fun validationMessage(errorKey: String?): String? =
    when (errorKey) {
        "invalid_dns_ip" -> stringResource(R.string.config_error_invalid_dns)
        "invalid_proxy_ip" -> stringResource(R.string.config_error_invalid_proxy_ip)
        "invalid_port" -> stringResource(R.string.config_error_invalid_port)
        "out_of_range" -> stringResource(R.string.config_error_out_of_range)
        "invalid_chain" -> stringResource(R.string.config_error_invalid_chain)
        "required" -> stringResource(R.string.config_error_required)
        "unsupported" -> stringResource(R.string.config_error_unsupported)
        "absolute_path" -> stringResource(R.string.config_relay_naive_path_error_absolute)
        else -> null
    }

internal fun editorPresetKind(uiState: ConfigUiState): ConfigPresetKind =
    uiState.editingPreset?.kind ?: ConfigPresetKind.Custom
