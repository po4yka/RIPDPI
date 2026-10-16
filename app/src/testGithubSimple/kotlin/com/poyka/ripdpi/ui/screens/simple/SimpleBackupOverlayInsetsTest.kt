package com.poyka.ripdpi.ui.screens.simple

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.poyka.ripdpi.backup.BackupRestoreUiState
import com.poyka.ripdpi.ui.screens.settings.BackupRestoreScreen
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w360dp-h640dp-mdpi")
class SimpleBackupOverlayInsetsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `backup Back stays below status bar at normal font`() {
        assertSafeBack(fontScale = 1f)
    }

    @Test
    fun `backup Back stays below status bar at maximum font`() {
        assertSafeBack(fontScale = 2f)
    }

    @Test
    fun `backup overlay consumes an inset already handled by its parent`() {
        assertSafeBack(fontScale = 1f, parentHandlesInsets = true)
    }

    private fun assertSafeBack(
        fontScale: Float,
        parentHandlesInsets: Boolean = false,
    ) {
        val insets = WindowInsets(left = 12, top = 52, right = 16, bottom = 24)
        var backs = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                RipDpiTheme {
                    val parentModifier = if (parentHandlesInsets) Modifier.windowInsetsPadding(insets) else Modifier
                    Box(parentModifier) {
                        SimpleBackupOverlay(insets = insets) {
                            BackupRestoreScreen(
                                state = BackupRestoreUiState(),
                                snackbarHostState = SnackbarHostState(),
                                onBack = { backs++ },
                                onExportClick = {},
                                onShareRedactedClick = {},
                                onImportClick = {},
                                onResetClick = { error("Simple must not expose factory reset") },
                                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.SimpleBackupRestoreScreen),
                                showResetAction = false,
                            )
                        }
                    }
                }
            }
        }
        val screen =
            composeRule.onNodeWithTag(RipDpiTestTags.SimpleBackupRestoreScreen).fetchSemanticsNode().boundsInRoot
        assertEquals("Apply the top safe inset once", 52f, screen.top, 0.5f)
        assertEquals("Respect the left cutout inset", 12f, screen.left, 0.5f)
        val back = composeRule.onNodeWithContentDescription("Navigate back").assertIsDisplayed()
        assertTrue("Back must be below the status bar", back.fetchSemanticsNode().boundsInRoot.top >= 52f)
        back.performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }
}
