package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.RelayChainHopUiState

/** Status and latency of one relay hop, for example "Connected · 24 ms", or null when neither is known. */
internal val RelayChainHopUiState.displayLabel: String?
    @Composable get() =
        listOfNotNull(
            statusLabel,
            latencyMs?.let { stringResource(R.string.unit_milliseconds_format, it) },
        ).joinToString(" · ")
            .ifBlank { null }
