package com.poyka.ripdpi.ui.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.screens.settings.WarpEnrollmentUiState
import com.poyka.ripdpi.ui.screens.settings.WarpProfileSetupRows
import com.poyka.ripdpi.ui.screens.settings.WarpSetupFailure
import com.poyka.ripdpi.ui.screens.settings.WarpSetupOperation
import com.poyka.ripdpi.ui.theme.RipDpiTheme

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun WarpProfileSetupIdlePreview() = WarpProfileSetupPreview(WarpEnrollmentUiState())

@Preview(showBackground = true, widthDp = 360, locale = "ar")
@Composable
private fun WarpProfileSetupBusyRtlPreview() =
    WarpProfileSetupPreview(WarpEnrollmentUiState(operation = WarpSetupOperation.Registration))

@Preview(showBackground = true, widthDp = 360, fontScale = 1.3f)
@Composable
private fun WarpProfileSetupCancellingPreview() =
    WarpProfileSetupPreview(
        WarpEnrollmentUiState(operation = WarpSetupOperation.Registration, cancellationRequested = true),
    )

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun WarpProfileSetupFailurePreview() =
    WarpProfileSetupPreview(WarpEnrollmentUiState(failure = WarpSetupFailure.Import))

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun WarpProfileSetupSavedPreview() = WarpProfileSetupPreview(WarpEnrollmentUiState(saved = true))

@Composable
private fun WarpProfileSetupPreview(state: WarpEnrollmentUiState) {
    RipDpiTheme {
        RipDpiCard {
            WarpProfileSetupRows(
                state = state,
                enabled = true,
                onRegisterProfile = {},
                onImportProfile = {},
                onCancelSetup = {},
            )
        }
    }
}
