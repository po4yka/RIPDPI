package com.poyka.ripdpi.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.data.RelayMieruMultiplexingHigh
import com.poyka.ripdpi.data.RelayMieruProtocolUdp
import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.services.OwnedStackBrowserSupport
import com.poyka.ripdpi.ui.localization.assertNoClippedText
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsEditorField
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileEditorState
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileScreen
import com.poyka.ripdpi.ui.screens.anytls.AnyTlsProfileUiState
import com.poyka.ripdpi.ui.screens.browser.OwnedStackBrowserScreen
import com.poyka.ripdpi.ui.screens.browser.OwnedStackBrowserUiState
import com.poyka.ripdpi.ui.screens.customization.AboutScreen
import com.poyka.ripdpi.ui.screens.diagnostics.PcapCaptureListScreen
import com.poyka.ripdpi.ui.screens.diagnostics.ReplayHistoryScreen
import com.poyka.ripdpi.ui.screens.mieru.MieruEditorField
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileEditorState
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileScreen
import com.poyka.ripdpi.ui.screens.mieru.MieruProfileUiState
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPages
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreen
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreenActions
import com.poyka.ripdpi.ui.screens.routes.RuleEditorPersistenceActions
import com.poyka.ripdpi.ui.screens.routes.RuleEditorScreen
import com.poyka.ripdpi.ui.screens.routes.RuleEditorUiState
import com.poyka.ripdpi.ui.screens.settings.DataTransparencyScreen
import com.poyka.ripdpi.ui.screens.ssh.SshEditorField
import com.poyka.ripdpi.ui.screens.ssh.SshProfileEditorState
import com.poyka.ripdpi.ui.screens.ssh.SshProfileScreen
import com.poyka.ripdpi.ui.screens.ssh.SshProfileUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** REQ-UIX-M3L-REMAINING: the remaining screen families keep every text and primary action in Arabic at 200% font. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ar-w360dp-h900dp")
class ArabicLargeFontScreensTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun onboardingPagesKeepTheirTextAndContinueAction() {
        OnboardingPages.indices.forEach { page ->
            render {
                OnboardingScreen(
                    uiState = OnboardingUiState(currentPage = page, totalPages = OnboardingPages.size),
                    actions = OnboardingScreenActions(),
                )
            }
            composeRule.assertNoClippedText("onboarding page $page")
            val primary =
                if (page == OnboardingPages.lastIndex) {
                    RipDpiTestTags.OnboardingValidateAction
                } else {
                    RipDpiTestTags.OnboardingContinue
                }
            composeRule.onNodeWithTag(primary).assertIsDisplayed()
        }
    }

    @Test
    fun aboutAndDataTransparencyKeepTheirText() {
        render { AboutScreen(onBack = {}) }
        composeRule.assertNoClippedText("about")
        render { DataTransparencyScreen(onBack = {}) }
        composeRule.assertNoClippedText("data transparency")
    }

    @Test
    fun ruleEditorKeepsTextAndSaveAction() {
        render {
            RuleEditorScreen(
                state = RuleEditorUiState(loaded = true),
                onBack = {},
                onNameChange = {},
                onEnabledChange = {},
                onDomainsChange = {},
                onIpCidrsChange = {},
                onPortsChange = {},
                onSourcePortsChange = {},
                onNetworkChange = {},
                onProcessNameChange = {},
                onPackagesChange = {},
                onOutboundChange = {},
                persistenceActions = RuleEditorPersistenceActions(save = {}),
            )
        }
        composeRule.onNodeWithTag(RipDpiTestTags.RuleEditorSave).assertIsDisplayed()
        composeRule.assertNoClippedText("rule editor")
    }

    @Test
    fun relayProfileEditorsKeepTextAndSaveAction() {
        render {
            SshProfileScreen(
                uiState =
                    SshProfileUiState(
                        editor =
                            SshProfileEditorState(
                                rawTextByField =
                                    mapOf(
                                        SshEditorField.DISPLAY_NAME to "Home bastion",
                                        SshEditorField.SERVER to "bastion.example.com",
                                        SshEditorField.SERVER_PORT to "22",
                                        SshEditorField.USERNAME to "deploy",
                                    ),
                                authType = RelaySshAuthTypePassword,
                                strictHostKey = true,
                            ),
                    ),
                onBack = {},
                onFieldChanged = { _, _ -> },
                onAuthTypeSelected = {},
                onStrictHostKeyChanged = {},
                onRevealPrivateKey = {},
                onRevealPassphrase = {},
                onSave = {},
            )
        }
        composeRule.onNodeWithText(text(R.string.ssh_save_action)).assertIsDisplayed()
        composeRule.assertNoClippedText("ssh editor")

        render {
            MieruProfileScreen(
                uiState =
                    MieruProfileUiState(
                        editor =
                            MieruProfileEditorState(
                                rawTextByField =
                                    mapOf(
                                        MieruEditorField.DISPLAY_NAME to "Home Mieru",
                                        MieruEditorField.SERVER to "mieru.example.com",
                                        MieruEditorField.SERVER_PORT to "8443",
                                    ),
                                protocol = RelayMieruProtocolUdp,
                                multiplexing = RelayMieruMultiplexingHigh,
                            ),
                    ),
                onBack = {},
                onFieldChanged = { _, _ -> },
                onProtocolSelected = {},
                onMultiplexingSelected = {},
                onSave = {},
            )
        }
        composeRule.onNodeWithText(text(R.string.mieru_save_action)).assertIsDisplayed()
        composeRule.assertNoClippedText("mieru editor")

        render {
            AnyTlsProfileScreen(
                uiState =
                    AnyTlsProfileUiState(
                        editor =
                            AnyTlsProfileEditorState(
                                rawTextByField =
                                    mapOf(
                                        AnyTlsEditorField.DISPLAY_NAME to "Frankfurt AnyTLS",
                                        AnyTlsEditorField.SERVER to "anytls.example.net",
                                        AnyTlsEditorField.SERVER_PORT to "8443",
                                    ),
                            ),
                    ),
                onBack = {},
                onFieldChanged = { _, _ -> },
                onSave = {},
            )
        }
        composeRule.onNodeWithText(text(R.string.anytls_save_action)).assertIsDisplayed()
        composeRule.assertNoClippedText("anytls editor")
    }

    @Test
    fun captureAndReplayScreensKeepTheirText() {
        render {
            PcapCaptureListScreen(
                captures = persistentListOf(),
                onCaptureSelected = {},
                onBack = {},
                onOpenAdvancedSettings = {},
            )
        }
        composeRule.assertNoClippedText("pcap capture list")
        render { ReplayHistoryScreen(replays = persistentListOf(), onRunScan = {}, onBack = {}) }
        composeRule.onNodeWithTag(RipDpiTestTags.ReplayHistoryRunScanAction).assertIsDisplayed()
        composeRule.assertNoClippedText("replay history")
    }

    @Test
    fun ownedStackBrowserKeepsItsText() {
        render {
            OwnedStackBrowserScreen(
                uiState =
                    OwnedStackBrowserUiState(
                        support =
                            OwnedStackBrowserSupport(
                                platformHttpEngineAvailable = true,
                                android17EchEligible = false,
                            ),
                    ),
                onBack = {},
                onUrlChanged = {},
                onOpen = {},
            )
        }
        composeRule.assertNoClippedText("owned stack browser")
    }

    private var screen by mutableStateOf<@Composable () -> Unit>({})
    private var hosted = false

    private fun render(content: @Composable () -> Unit) {
        if (!hosted) {
            hosted = true
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    RipDpiTheme { key(screen) { screen() } }
                }
            }
        }
        composeRule.runOnIdle { screen = content }
        composeRule.waitForIdle()
    }

    private fun text(id: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
}
