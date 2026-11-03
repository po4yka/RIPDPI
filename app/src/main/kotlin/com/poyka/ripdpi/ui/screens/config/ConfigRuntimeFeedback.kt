package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigRuntimeOperation
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

data class ConfigRuntimeCallbacks(
    val dismissError: () -> Unit = {},
)

data class ConfigRuntimeIntegration(
    val operation: ConfigRuntimeOperation? = null,
    val error: String? = null,
    val callbacks: ConfigRuntimeCallbacks = ConfigRuntimeCallbacks(),
)

@Composable
internal fun ConfigRuntimeFeedback(
    uiState: ConfigUiState,
    onDismissRuntimeError: () -> Unit,
) {
    uiState.runtimeError?.let { message ->
        val requester = remember { BringIntoViewRequester() }
        var isLaidOut by remember(message) { mutableStateOf(false) }
        LaunchedEffect(message, isLaidOut) {
            if (isLaidOut) requester.bringIntoView()
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(requester)
                    .onGloballyPositioned { isLaidOut = true },
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            WarningBanner(
                title = stringResource(R.string.home_status_error_title),
                message = message,
                tone = WarningBannerTone.Error,
                testTag = RipDpiTestTags.ConfigRuntimeError,
                onDismiss = onDismissRuntimeError,
            )
        }
    }
}
