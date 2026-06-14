package com.poyka.ripdpi.ui.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import com.poyka.ripdpi.backup.BackupRestoreUiState
import com.poyka.ripdpi.ui.screens.settings.BackupRestoreScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BackupRestoreScreenshotTest {
    @Test
    fun idle() {
        capture("idle", BackupRestoreUiState())
    }

    @Test
    fun busy() {
        capture(
            "busy",
            BackupRestoreUiState(
                exporting = true,
                sharing = true,
                importing = true,
                restoring = true,
            ),
        )
    }

    @Test
    fun policyBlocked() {
        capture("policyBlocked", BackupRestoreUiState(exportDisabledByPolicy = true))
    }

    private fun capture(
        name: String,
        state: BackupRestoreUiState,
    ) {
        captureScreenBothThemes(name = name, widthDp = 420, heightDp = 1200, testClassFqn = FQN) {
            BackupRestoreScreen(
                state = state,
                snackbarHostState = remember { SnackbarHostState() },
                onBack = {},
                onExportClick = {},
                onShareRedactedClick = {},
                onImportClick = {},
                onResetClick = {},
            )
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.BackupRestoreScreenshotTest"
    }
}
