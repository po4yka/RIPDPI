package com.poyka.ripdpi.ui.screens.settings

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.StrategyPackCatalogUiState
import com.poyka.ripdpi.ui.state.SettingsUiState
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
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class AdvancedSettingsImeInsetsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `keyboard reduces the scroll viewport and leaves the toolbar anchored`() {
        assertKeyboardGeometry()
    }

    @Test
    fun `maximum font keeps the toolbar anchored while the keyboard reduces the viewport`() {
        assertKeyboardGeometry(fontScale = 2f)
    }

    @Test
    fun `parent system bar consumption is counted once when the keyboard opens`() {
        assertKeyboardGeometry(parentOwnsSystemBars = true)
    }

    @Test
    fun `parent keyboard consumption leaves no second keyboard gap`() {
        assertKeyboardGeometry(parentOwnsSystemBars = true, parentOwnsIme = true)
    }

    private fun assertKeyboardGeometry(
        fontScale: Float = 1f,
        parentOwnsSystemBars: Boolean = false,
        parentOwnsIme: Boolean = false,
    ) {
        lateinit var view: View
        var density = 1f
        var backClicks = 0
        composeRule.setContent {
            view = LocalView.current
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                RipDpiTheme {
                    Box(Modifier.fillMaxSize().testTag("test-window")) {
                        val parentInsets =
                            if (parentOwnsIme) {
                                Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                            } else if (parentOwnsSystemBars) {
                                Modifier.windowInsetsPadding(WindowInsets.systemBars)
                            } else {
                                Modifier
                            }
                        Box(parentInsets) {
                            AdvancedSettingsScreen(
                                uiState = SettingsUiState(),
                                hostPackCatalog = HostPackCatalogUiState(),
                                strategyPackCatalog = StrategyPackCatalogUiState(),
                                notice = null,
                                actions = actions { backClicks++ },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        val systemBarHeight = if (parentOwnsSystemBars) (24 * density).toInt() else 0
        val keyboardHeight = (180 * density).toInt()
        dispatchInsets(view, systemBarHeight = systemBarHeight)
        val back = composeRule.onNodeWithContentDescription(view.context.getString(R.string.navigation_back))
        val body = composeRule.onNode(hasScrollToKeyAction())
        val hiddenToolbar = back.fetchSemanticsNode().boundsInRoot
        val hiddenBody = body.fetchSemanticsNode().boundsInRoot
        val window = composeRule.onNodeWithTag("test-window").fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Only the parent owns the hidden navigation bar inset",
            window.bottom - systemBarHeight,
            hiddenBody.bottom,
            1f,
        )

        assertTrue(
            "Back must stay below the parent status bar inset",
            hiddenToolbar.top >= window.top + systemBarHeight,
        )

        dispatchInsets(view, keyboardHeight, systemBarHeight)

        val visibleBody = body.fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Toolbar must stay at its hidden-keyboard position",
            hiddenToolbar,
            back.fetchSemanticsNode().boundsInRoot,
        )
        assertEquals(
            "Scroll viewport must end above the keyboard",
            window.bottom - keyboardHeight,
            visibleBody.bottom,
            1f,
        )
        assertEquals(hiddenBody.top, visibleBody.top, 1f)
        back.assertIsDisplayed().performClick()
        assertEquals(1, backClicks)

        dispatchInsets(view, systemBarHeight = systemBarHeight)
        assertEquals(
            "Closing the keyboard must restore the viewport",
            hiddenBody,
            body.fetchSemanticsNode().boundsInRoot,
        )
    }

    private fun dispatchInsets(
        view: View,
        keyboardHeight: Int = 0,
        systemBarHeight: Int = 0,
    ) {
        composeRule.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(
                view,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardHeight))
                    .setVisible(WindowInsetsCompat.Type.ime(), keyboardHeight > 0)
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, systemBarHeight, 0, 0))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), systemBarHeight > 0)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, systemBarHeight))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), systemBarHeight > 0)
                    .build(),
            )
        }
        composeRule.waitForIdle()
    }

    private fun actions(onBack: () -> Unit) =
        AdvancedSettingsActions(
            onBack = onBack,
            onOpenStrategyConfig = {},
            onOpenBlockcheck = {},
            onOpenAssetProvider = {},
            onOpenRememberedNetworks = {},
            onToggleChanged = { _, _ -> },
            onTextConfirmed = { _, _ -> },
            onOptionSelected = { _, _ -> },
            onApplyHostPackPreset = { _, _, _ -> },
            onRefreshHostPackCatalog = {},
            onRefreshStrategyPackCatalog = {},
            onForgetLearnedHosts = {},
            onClearRememberedNetworks = {},
            onWsTunnelModeChanged = {},
            onSaveWsTunnelWorkerTransport = { _, _, _ -> },
            onClearWsTunnelWorkerTransport = {},
            onRotateSalt = {},
            onSaveActivationRange = { _, _, _ -> },
            onResetAdaptiveSplit = {},
            onResetAdaptiveFakeTtlProfile = {},
            onResetActivationWindow = {},
            onResetHttpParserEvasions = {},
            onResetFakePayloadLibrary = {},
            onResetFakeTlsProfile = {},
            onRoutingPolicyModeSelected = {},
            onDhtMitigationModeSelected = {},
            onAntiCorrelationEnabledChanged = {},
            onAppRoutingPresetEnabledChanged = { _, _ -> },
        )
}
