package com.poyka.ripdpi.ui.screens.proxyimport

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.fakes.FakeAppSettingsRepository
import com.poyka.ripdpi.data.fakes.FakeRelayCredentialStore
import com.poyka.ripdpi.data.fakes.FakeRelayProfileStore
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The confirmation screen closes on success and on a lost import; each must say what happened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportResultFeedbackTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a lost import says so before the screen closes`() {
        var unavailable = 0
        render(importToken = "gone", onUnavailable = { unavailable++ })

        composeRule.waitUntil { unavailable == 1 }
        assertEquals(context.getString(R.string.import_unavailable), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a saved import names what it added and Connect selects it`() {
        val profile = ProxyProfile.Trojan("p1", "Tokyo", "", "relay.example", 443, "fixture-password")
        val selected = mutableListOf<Boolean>()
        render(importToken = PendingProxyImportStore.process.stage(profile), onSelected = { selected.add(it) })

        composeRule.onNodeWithText(context.getString(R.string.import_confirm_add_action)).performClick()

        val connect = context.getString(R.string.vpn_connect)
        composeRule.waitUntil { composeRule.onAllNodesWithText(connect).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(context.getString(R.string.import_done_added, "Tokyo")).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.profile_variants_cta_select)).assertExists()
        composeRule.onNodeWithText(connect).performClick()
        composeRule.waitUntil { selected == listOf(true) }
    }

    private fun render(
        importToken: String,
        onImported: () -> Unit = {},
        onSelected: (Boolean) -> Unit = {},
        onUnavailable: () -> Unit = {},
    ) {
        val viewModel =
            ProfileImportConfirmViewModel(
                RelayProfileActivator(
                    TestDirectRelayProfileMutationCoordinator(
                        FakeRelayProfileStore(),
                        FakeRelayCredentialStore(),
                        FakeAppSettingsRepository(),
                    ),
                ),
                serverSelection = { ImportedServerSelectionResult.Selected },
            )
        composeRule.setContent {
            RipDpiTheme {
                ProfileImportConfirmRoute(
                    importToken = importToken,
                    onBack = {},
                    onImported = onImported,
                    onSelected = onSelected,
                    onUnavailable = onUnavailable,
                    viewModel = viewModel,
                )
            }
        }
    }
}
