package com.poyka.ripdpi.ui.screens.config

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
                    onRelayServerChanged = {},
                    onRelayServerPortChanged = {},
                    onRelayServerNameChanged = {},
                    onRelayRealityPublicKeyChanged = {},
                    onRelayRealityShortIdChanged = {},
                    onRelayVlessTransportChanged = {},
                    onRelayXhttpPathChanged = {},
                    onRelayXhttpHostChanged = {},
                    onRelayVlessUuidChanged = {},
                    onRelayHysteriaPasswordChanged = {},
                    onRelayHysteriaSalamanderKeyChanged = {},
                    onRelayChainEntryProfileIdChanged = {},
                    onRelayChainExitProfileIdChanged = {},
                    onRelayMasqueUrlChanged = {},
                    onRelayMasqueAuthModeChanged = {},
                    onRelayMasqueAuthTokenChanged = {},
                    onRelayMasqueClientCertificateChainPemChanged = {},
                    onRelayMasqueClientPrivateKeyPemChanged = {},
                    onRelayMasqueUseHttp2FallbackChanged = {},
                    onRelayMasqueCloudflareGeohashEnabledChanged = {},
                    onRelayMasqueImportCertificateChainClicked = {},
                    onRelayMasqueImportPrivateKeyClicked = {},
                    onRelayMasqueImportPkcs12Clicked = {},
                    onRelayTuicUuidChanged = {},
                    onRelayTuicPasswordChanged = {},
                    onRelayTuicZeroRttChanged = {},
                    onRelayTuicCongestionControlChanged = {},
                    onRelayShadowTlsPasswordChanged = {},
                    onRelayShadowTlsInnerProfileIdChanged = {},
                    onRelayNaiveUsernameChanged = {},
                    onRelayNaivePasswordChanged = {},
                    onRelayNaivePathChanged = {},
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
                    onRelayServerChanged = {},
                    onRelayServerPortChanged = {},
                    onRelayServerNameChanged = {},
                    onRelayRealityPublicKeyChanged = {},
                    onRelayRealityShortIdChanged = {},
                    onRelayVlessTransportChanged = {},
                    onRelayXhttpPathChanged = {},
                    onRelayXhttpHostChanged = {},
                    onRelayVlessUuidChanged = {},
                    onRelayHysteriaPasswordChanged = {},
                    onRelayHysteriaSalamanderKeyChanged = {},
                    onRelayChainEntryProfileIdChanged = {},
                    onRelayChainExitProfileIdChanged = {},
                    onRelayMasqueUrlChanged = {},
                    onRelayMasqueAuthModeChanged = {},
                    onRelayMasqueAuthTokenChanged = {},
                    onRelayMasqueClientCertificateChainPemChanged = {},
                    onRelayMasqueClientPrivateKeyPemChanged = {},
                    onRelayMasqueUseHttp2FallbackChanged = {},
                    onRelayMasqueCloudflareGeohashEnabledChanged = {},
                    onRelayMasqueImportCertificateChainClicked = {},
                    onRelayMasqueImportPrivateKeyClicked = {},
                    onRelayMasqueImportPkcs12Clicked = {},
                    onRelayTuicUuidChanged = {},
                    onRelayTuicPasswordChanged = {},
                    onRelayTuicZeroRttChanged = {},
                    onRelayTuicCongestionControlChanged = {},
                    onRelayShadowTlsPasswordChanged = {},
                    onRelayShadowTlsInnerProfileIdChanged = {},
                    onRelayNaiveUsernameChanged = {},
                    onRelayNaivePasswordChanged = {},
                    onRelayNaivePathChanged = {},
                )
            }
        }

        composeRule.onNodeWithText("MASQUE auth token").assertExists()
        composeRule.onNodeWithText("Client certificate chain (PEM)").assertDoesNotExist()
        composeRule.onNodeWithText("Client private key (PEM)").assertDoesNotExist()
    }
}
