package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticProfileSearchTest {
    @get:Rule val composeRule = createComposeRule()
    private val profiles =
        listOf(
            DiagnosticsProfileOptionUiModel(
                "web",
                "Website check",
                "bundled",
                family = DiagnosticProfileFamily.WEB_CONNECTIVITY,
            ),
            DiagnosticsProfileOptionUiModel(
                "chat",
                "Chat check",
                "imported",
                family = DiagnosticProfileFamily.MESSAGING,
            ),
        ).toImmutableList()
    private val selected = mutableListOf<String>()
    private var probes = 0
    private val scope = RipDpiTestTags.DiagnosticProfileSearch

    @Test fun `description and source search preserve selection`() {
        composeRule.setContent { Content() }
        open()
        val description = RuntimeEnvironment.getApplication().getString(R.string.diagnostics_profile_desc_messaging)
        query(description)
        composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsProfile("web")).assertDoesNotExist()
        composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsProfile("chat")).assertExists()
        query("bundled")
        composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsProfile("web")).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsProfile("chat")).assertDoesNotExist()
        assertNoActions()
    }

    @Test fun `diagnostic query family and selected id survive recreation and sheet dismissal`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { Content() }
        open()
        query("Chat")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "MESSAGING")).performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchQuery(scope)).assertTextContains("Chat")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "MESSAGING")).assertIsSelected()
        cancel()
        open()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchQuery(scope)).assertTextContains("Chat")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchFilter(scope, "MESSAGING")).assertIsSelected()
        assertNoActions()
    }

    @Test fun `empty reset restores grouped profiles and explicit selection happens exactly once`() {
        composeRule.setContent { Content() }
        open()
        query("missing")
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchEmpty(scope)).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchReset(scope)).performScrollTo().performClick()
        composeRule.onNodeWithTag(RipDpiTestTags.diagnosticsProfile("chat")).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("chat"), selected)
        assertEquals(0, probes)
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchSheet).assertDoesNotExist()
    }

    @Test fun `real modal Back preserves selection and does not run scans`() {
        composeRule.setContent { Content() }
        open()
        query("chat")
        composeRule.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchSheet).assertDoesNotExist()
        assertNoActions()
    }

    @Test fun `drag dismissal preserves selection and does not run scans`() {
        composeRule.setContent { Content() }
        open()
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchSheet).performTouchInput { swipeDown() }
        composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchSheet).assertDoesNotExist()
        assertNoActions()
    }

    @Composable private fun Content() {
        RipDpiTheme {
            ScanSection(
                scan =
                    DiagnosticsScanUiModel(
                        profiles = profiles,
                        selectedProfileId = "web",
                        selectedProfile = profiles.first(),
                    ),
                onSelectProfile = { selected += it },
                onRunRawScan = { probes++ },
                onRunInPathScan = { probes++ },
                onCancelScan = { probes++ },
                onOpenAdvancedSettings = {},
                onOpenDnsSettings = {},
                onRequestVpnPermission = {},
                onSelectStrategyProbeCandidate = {},
                onSelectProbe = {},
                onOpenHistory = {},
                onOpenModeEditor = {},
                onOpenOwnedStackBrowser = {},
            )
        }
    }

    private fun open() = composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticProfileSearchOpen).performClick()

    private fun query(value: String) =
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchQuery(scope)).performTextReplacement(value)

    private fun cancel() =
        composeRule.onNodeWithTag(RipDpiTestTags.profileSearchCancel(scope)).performScrollTo().performClick()

    private fun assertNoActions() {
        assertEquals(emptyList<String>(), selected)
        assertEquals(0, probes)
    }
}
