package com.poyka.ripdpi.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.RememberedNetworksUiState
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.xray.XrayCapability
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.subscription.SubscriptionDetailUiState
import com.poyka.ripdpi.subscription.SubscriptionExpiryItemUiState
import com.poyka.ripdpi.subscription.SubscriptionExpiryStatus
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.ui.screens.routes.AppPickerSheet
import com.poyka.ripdpi.ui.screens.routes.InstalledAppItem
import com.poyka.ripdpi.ui.screens.routes.RoutesScreen
import com.poyka.ripdpi.ui.screens.routes.RoutesUiState
import com.poyka.ripdpi.ui.screens.routes.RuleEditorFailure
import com.poyka.ripdpi.ui.screens.routes.RuleEditorPersistenceActions
import com.poyka.ripdpi.ui.screens.routes.RuleEditorScreen
import com.poyka.ripdpi.ui.screens.routes.RuleEditorUiState
import com.poyka.ripdpi.ui.screens.routes.RuleRow
import com.poyka.ripdpi.ui.screens.settings.RememberedNetworksScreen
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionStatusScreen
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionStatusUiState
import com.poyka.ripdpi.ui.screens.xray.XrayImportRestoreStatus
import com.poyka.ripdpi.ui.screens.xray.XrayImportUiState
import com.poyka.ripdpi.ui.screens.xray.XrayProfileImportScreen
import com.poyka.ripdpi.ui.screens.xray.stringIdFor
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1200dp")
class SetupInteractionQualityTest {
    @get:Rule val composeRule = createComposeRule()

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun `routing switch names the rule and does not edit it`() {
        var toggles = 0
        var edits = 0
        composeRule.setContent {
            RipDpiTheme {
                RoutesScreen(
                    state =
                        RoutesUiState(
                            rows = persistentListOf(RuleRow(RuleEntity(id = 1, name = "Video"), "Proxy")),
                        ),
                    onBack = {},
                    onAddRule = {},
                    onEditRule = { edits++ },
                    onToggleEnabled = { toggles++ },
                    onDelete = {},
                    onReorder = {},
                )
            }
        }
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasContentDescription("Video"),
            ).performClick()
        assertEquals(1, toggles)
        assertEquals(0, edits)
    }

    @Test
    fun `capability information has no action`() {
        val capability = XrayCapability.entries.first()
        composeRule.setContent {
            RipDpiTheme {
                XrayProfileImportScreen(
                    uiState =
                        XrayImportUiState(
                            selectedOption = XrayServiceModeOption.XrayVpn,
                            restoreStatus = XrayImportRestoreStatus.Ready,
                            acceptedConfigReady = true,
                            capabilities = persistentListOf(capability),
                        ),
                    onBack = {},
                    onSelectOption = {},
                    onRawInputChange = {},
                    onValidate = {},
                    onRetryRestore = {},
                    onConfirm = {},
                )
            }
        }
        composeRule.onNodeWithText(text(stringIdFor(capability.titleKey))).performScrollTo().assertHasNoClickAction()
    }

    @Test
    fun `remembered networks shows progress before data arrives`() {
        composeRule.setContent {
            RipDpiTheme {
                RememberedNetworksScreen(RememberedNetworksUiState(), {}, {}, {})
            }
        }
        composeRule.onNode(hasContentDescription(text(R.string.cd_loading))).assertExists()
        composeRule.onNodeWithTag(RipDpiTestTags.RememberedNetworksEmpty).assertDoesNotExist()
    }

    @Test
    fun `app picker explains an unmatched query and clears it without losing selection`() {
        var picked: Set<String>? = null
        composeRule.setContent {
            RipDpiTheme {
                AppPickerSheet(
                    apps = listOf(InstalledAppItem("video.app", "Video")),
                    initialSelection = setOf("video.app"),
                    onConfirm = { picked = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.AppPickerSearch).performTextReplacement("unmatched")
        composeRule.onNodeWithText(text(R.string.ui_app_picker_no_matches)).assertExists()
        composeRule.onNodeWithText(text(R.string.ui_app_picker_clear_search)).performClick()
        composeRule.onNodeWithText("Video").assertExists()
        composeRule.onNodeWithText(text(R.string.app_picker_done)).performClick()
        assertEquals(setOf("video.app"), picked)
    }

    @Test
    fun `rule load failure offers a named retry action`() {
        var retries = 0
        composeRule.setContent {
            RipDpiTheme {
                RuleEditorScreen(
                    state = RuleEditorUiState(failure = RuleEditorFailure.Load),
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
                    persistenceActions = RuleEditorPersistenceActions(save = {}, retryLoad = { retries++ }),
                )
            }
        }
        composeRule.onNodeWithText(text(R.string.ui_rule_load_failed_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.startup_recovery_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `rule save failure retains the editable form`() {
        composeRule.setContent {
            RipDpiTheme {
                RuleEditorScreen(
                    state =
                        RuleEditorUiState(
                            loaded = true,
                            domains = "retained.example",
                            failure = RuleEditorFailure.Save,
                        ),
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
        }
        composeRule.onNodeWithText(text(R.string.ui_rule_save_failed_title)).assertExists()
        composeRule.onNodeWithText("retained.example").performScrollTo().assertExists()
    }

    @Test
    fun `all subscription refresh actions are disabled while one group refreshes`() {
        val details =
            SubscriptionDetailUiState(
                "redacted",
                "redacted",
                false,
                false,
                true,
                null,
                0,
                null,
                null,
            )
        val summary =
            SubscriptionExpirySummaryUiState(
                items =
                    persistentListOf(
                        SubscriptionExpiryItemUiState(
                            "first",
                            "First",
                            0,
                            SubscriptionExpiryStatus.UNKNOWN,
                            null,
                            details,
                            null,
                        ),
                        SubscriptionExpiryItemUiState(
                            "second",
                            "Second",
                            0,
                            SubscriptionExpiryStatus.UNKNOWN,
                            null,
                            details,
                            null,
                        ),
                    ),
            )
        composeRule.setContent {
            RipDpiTheme {
                SubscriptionStatusScreen(SubscriptionStatusUiState(summary, refreshingGroupId = "first"), {}, {}, {})
            }
        }
        val nodes = composeRule.onAllNodesWithText(text(R.string.subscription_status_refresh_action))
        nodes[0].assertIsNotEnabled()
        nodes[1].assertIsNotEnabled()
    }
}
