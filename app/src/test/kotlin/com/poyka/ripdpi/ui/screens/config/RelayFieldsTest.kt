package com.poyka.ripdpi.ui.screens.config

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayChain
import com.poyka.ripdpi.activities.ConfigFieldRelayNaivePath
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.RelayChainHopStatusUiState
import com.poyka.ripdpi.activities.RelayChainHopUiState
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayTrustDomainWarning
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RelayFieldsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anyTlsFieldsRenderEndpointSniAndPassword() {
        var editedPassword: String? = null
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft = ConfigDraft(relayKind = RelayKindAnyTls),
                    uiState = ConfigUiState(),
                    actions = RelayKindFieldActions(onAnyTlsPasswordChanged = { editedPassword = it }),
                )
            }
        }

        composeRule.onNodeWithText("Relay server").assertExists()
        composeRule.onNodeWithText("Relay port").assertExists()
        composeRule.onNodeWithText("TLS server name").assertExists()
        composeRule.onNodeWithText("Password").assertExists()
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextReplacement("owned-password-fixture")
        assertEquals("owned-password-fixture", editedPassword)
    }

    @Test
    fun naiveProxyFieldsRenderEndpointCredentialsAndPathInputs() {
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft = ConfigDraft(relayKind = RelayKindNaiveProxy),
                    uiState = ConfigUiState(),
                )
            }
        }

        composeRule.onNodeWithText("Relay server").assertExists()
        composeRule.onNodeWithText("Relay port").assertExists()
        composeRule.onNodeWithText("TLS server name").assertExists()
        composeRule.onNodeWithText("NaiveProxy username").assertExists()
        composeRule.onNodeWithText("Used for upstream HTTP Basic authentication.").assertExists()
        composeRule.onNodeWithText("NaiveProxy password").assertExists()
        composeRule.onNodeWithText("Stored in the secure relay credential store.").assertExists()
        composeRule.onNodeWithText("HTTP path (optional)").assertExists()
        composeRule
            .onNodeWithText(
                "Leave blank for the default CONNECT endpoint, or use an absolute path such as /proxy.",
            ).assertExists()
    }

    @Test
    fun naiveProxyPasswordHelperIsNotExposedAsFieldLabel() {
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft = ConfigDraft(relayKind = RelayKindNaiveProxy),
                    uiState = ConfigUiState(),
                )
            }
        }

        composeRule.onNodeWithText("NaiveProxy password").assertExists()
        composeRule
            .onAllNodesWithContentDescription(
                "Stored in the secure relay credential store.",
            ).assertCountEquals(0)
    }

    @Test
    fun naiveProxyInvalidPathShowsPathError() {
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft =
                        ConfigDraft(
                            relayKind = RelayKindNaiveProxy,
                            relayNaivePath = "proxy",
                        ),
                    uiState =
                        ConfigUiState(
                            validationErrors = persistentMapOf(ConfigFieldRelayNaivePath to "absolute_path"),
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Path must start with /.").assertExists()
    }

    @Test
    fun torFieldsRenderBridgeLineAndAnonymityLatencyCaveat() {
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft = ConfigDraft(relayKind = RelayKindTor),
                    uiState = ConfigUiState(),
                )
            }
        }

        composeRule.onNodeWithText("Bridge line").assertExists()
        composeRule
            .onNodeWithText(
                "Paste an obfs4 bridge line. Tor bootstraps only through this pluggable transport, " +
                    "never a direct connection.",
            ).assertExists()
        composeRule.onNodeWithText("Tor is slower and anonymized differently").assertExists()
        composeRule
            .onNodeWithText(
                "Traffic is relayed through the volunteer Tor network, so expect higher latency than a direct relay. " +
                    "Anonymity is provided by Tor, not by RIPDPI. Tor carries TCP and DNS only; UDP is not supported.",
            ).assertExists()
    }

    @Test
    fun masqueFieldsRenderCloudflareDirectChipAndMtlsInputs() {
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft =
                        ConfigDraft(
                            relayKind = RelayKindMasque,
                            relayMasqueAuthMode = RelayMasqueAuthModeCloudflareMtls,
                        ),
                    uiState =
                        ConfigUiState(
                            supportsMasquePrivacyPass = true,
                            masquePrivacyPassBuildStatus = MasquePrivacyPassBuildStatus.Available,
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Cloudflare Direct").assertExists()
        composeRule.onNodeWithText("Client certificate chain (PEM)").assertExists()
        composeRule.onNodeWithText("Client private key (PEM)").assertExists()
        composeRule.onNodeWithText("Import certificate").assertExists()
        composeRule.onNodeWithText("Import private key").assertExists()
        composeRule.onNodeWithText("Import PKCS#12 bundle").assertExists()
        composeRule.onNodeWithText("MASQUE auth token").assertDoesNotExist()
    }

    @Test
    fun masqueBearerModeKeepsTokenInputAndHidesMtlsInputs() {
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft =
                        ConfigDraft(
                            relayKind = RelayKindMasque,
                            relayMasqueAuthMode = RelayMasqueAuthModeBearer,
                        ),
                    uiState =
                        ConfigUiState(
                            supportsMasquePrivacyPass = true,
                            masquePrivacyPassBuildStatus = MasquePrivacyPassBuildStatus.Available,
                        ),
                )
            }
        }

        composeRule.onNodeWithText("MASQUE auth token").assertExists()
        composeRule.onNodeWithText("Client certificate chain (PEM)").assertDoesNotExist()
        composeRule.onNodeWithText("Client private key (PEM)").assertDoesNotExist()
    }

    @Test
    fun chainRelayFieldsRenderOrderedHopsTrustWarningAndAddControl() {
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft =
                        ConfigDraft(
                            relayKind = RelayKindChainRelay,
                            relayChainEntryProfileId = "entry",
                            relayChainExitProfileId = "exit",
                        ),
                    uiState =
                        ConfigUiState(
                            relayProfiles =
                                persistentListOf(
                                    RelayProfileUiState(
                                        id = "entry",
                                        kind = RelayKindVlessReality,
                                        kindLabel = "VLESS + Reality",
                                        jurisdiction = "RU",
                                        operatorName = "Entry Transit",
                                    ),
                                    RelayProfileUiState(
                                        id = "exit",
                                        kind = RelayKindMasque,
                                        kindLabel = "MASQUE",
                                        jurisdiction = "ru",
                                        operatorName = "Exit Transit",
                                    ),
                                ),
                            relayChainHopStatus =
                                RelayChainHopStatusUiState(
                                    entry = RelayChainHopUiState(statusLabel = "Connected", latencyLabel = "24 ms"),
                                    exit = RelayChainHopUiState(statusLabel = "Connecting", latencyLabel = "61 ms"),
                                ),
                            relayChainTrustWarning = RelayTrustDomainWarning(sharedJurisdiction = "RU"),
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Hop 1 · Entry").assertExists()
        composeRule.onNodeWithText("Hop 2 · Exit").assertExists()
        composeRule.onNodeWithText("entry · VLESS + Reality").assertExists()
        composeRule.onNodeWithText("Exit Transit · ru").assertExists()
        composeRule.onNodeWithText("Connected · 24 ms").assertExists()
        composeRule.onNodeWithText("Connecting · 61 ms").assertExists()
        composeRule.onNodeWithText("Shared trust domain").assertExists()
        composeRule.onNodeWithText("Each hop adds latency").assertExists()
        composeRule.onNodeWithText("Add hop").assertExists()
    }

    @Test
    fun chainRelayFieldsShowsValidationError() {
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft = ConfigDraft(relayKind = RelayKindChainRelay),
                    uiState =
                        ConfigUiState(
                            validationErrors = persistentMapOf(ConfigFieldRelayChain to "required"),
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Select both entry and exit profiles.").assertExists()
    }

    @Test
    fun chainRelayFieldsWarnWhenTrustMetadataIsMissing() {
        composeRule.setContent {
            RipDpiTheme {
                RelayKindFields(
                    draft =
                        ConfigDraft(
                            relayKind = RelayKindChainRelay,
                            relayChainEntryProfileId = "entry",
                            relayChainExitProfileId = "exit",
                        ),
                    uiState =
                        ConfigUiState(
                            relayChainTrustWarning =
                                RelayTrustDomainWarning(
                                    missingEntryTrustDomain = true,
                                ),
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Missing trust metadata").assertExists()
        composeRule.onNodeWithText("Entry hop is missing jurisdiction or operator metadata.").assertExists()
    }
}
