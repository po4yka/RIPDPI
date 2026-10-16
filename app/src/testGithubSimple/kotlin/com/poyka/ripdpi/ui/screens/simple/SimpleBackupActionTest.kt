package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.HomeDiagnosticsActionUiState
import com.poyka.ripdpi.activities.HomeDiagnosticsRunUiStatus
import com.poyka.ripdpi.activities.HomeDiagnosticsUiState
import com.poyka.ripdpi.activities.HomePauseUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w360dp-h640dp-mdpi")
class SimpleBackupActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `backup action is reachable at font two and opens only backup`() {
        var backupClicks = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SimpleBackupFixture(onOpenBackup = { backupClicks++ })
            }
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.SimpleBackupRestoreAction)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, backupClicks)
    }

    @Test
    fun `backup stays unavailable while automatic configuration runs`() {
        composeRule.setContent {
            SimpleBackupFixture(
                diagnostics =
                    HomeDiagnosticsUiState(
                        analysisRunStatus = HomeDiagnosticsRunUiStatus.RUNNING,
                        analysisAction = HomeDiagnosticsActionUiState(busy = true),
                    ),
            )
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.SimpleBackupRestoreAction)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis)
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun `backup stays unavailable during startup before busy projection arrives`() {
        composeRule.setContent {
            SimpleBackupFixture(
                diagnostics = HomeDiagnosticsUiState(analysisRunStatus = HomeDiagnosticsRunUiStatus.STARTING),
            )
        }
        composeRule
            .onNodeWithTag(RipDpiTestTags.SimpleBackupRestoreAction)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis)
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun `analysis action names automatic configuration and discloses saved changes before launch`() {
        composeRule.setContent { SimpleBackupFixture() }
        composeRule
            .onNodeWithText("This check can save DNS, bypass strategy and WARP settings automatically.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeDiagnosticsRunAnalysis)
            .performScrollTo()
            .assertTextEquals("Check and configure connection")
    }
}

@Composable
private fun SimpleBackupFixture(
    diagnostics: HomeDiagnosticsUiState = HomeDiagnosticsUiState(),
    onOpenBackup: () -> Unit = { error("Backup must stay disabled") },
) {
    RipDpiTheme {
        SimpleHomeContent(
            pauseState = HomePauseUiState(),
            onPause = { error("Backup must not pause a connection") },
            onResumePause = { error("Backup must not resume a connection") },
            onStopPause = { error("Backup must not stop a connection") },
            connectionState = ConnectionState.Disconnected,
            diagnostics = diagnostics,
            activeTransport = null,
            snackbarHostState = SnackbarHostState(),
            onToggleConnection = { error("Backup must not change a connection") },
            onRunReport = { error("Backup must not start analysis") },
            onCancelReport = { error("Backup must not cancel analysis") },
            onOpenProfiles = { error("Backup must not open profiles") },
            onOpenBackup = onOpenBackup,
            onOpenAbout = { error("Backup must not open About") },
        )
    }
}
