package com.poyka.ripdpi.ui.screens.config

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldRelayNaivePath
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentMapOf
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
}
