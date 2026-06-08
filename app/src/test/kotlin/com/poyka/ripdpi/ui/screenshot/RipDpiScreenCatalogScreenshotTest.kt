package com.poyka.ripdpi.ui.screenshot

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.OnboardingValidationState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.RipDpiAboutPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiAdvancedSettingsPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiBiometricPinPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiBiometricPromptPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiConfigPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiCustomizationPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDataTransparencyPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDetectionCheckDarkPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDetectionCheckPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDiagnosticsScanPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDiagnosticsSharePreviewScene
import com.poyka.ripdpi.ui.components.RipDpiDnsSettingsPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHistoryPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeCompactPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeConnectingPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeDarkPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeDisconnectedPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeErrorPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeExpandedPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiHomeHighContrastPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiIntroLargeFontPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiLogsEmptyDarkPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiLogsPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiModeEditorInvalidChainPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiModeEditorPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiSettingsDarkPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiSettingsMediumPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiStrategyConfigPreviewScene
import com.poyka.ripdpi.ui.components.RipDpiVpnPermissionDialogPreviewScene
import com.poyka.ripdpi.ui.screens.diagnostics.ReplayHistoryScreen
import com.poyka.ripdpi.ui.screens.health.ConnectionHealthScreen
import com.poyka.ripdpi.ui.screens.health.previewConnectionHealthUiState
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingInfoPageCount
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPages
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreen
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreenActions
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionFailoverScreen
import com.poyka.ripdpi.ui.screens.subscription.previewSubscriptionFailoverUiState
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerScreen
import com.poyka.ripdpi.ui.screens.tuner.previewStrategyTunerUiState
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiScreenCatalogScreenshotTest {
    @Test
    fun homeExpandedScreen() {
        captureRipDpiScreenshot(widthDp = 1040, heightDp = 920) {
            RipDpiHomeExpandedPreviewScene()
        }
    }

    @Test
    fun settingsMediumScreen() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 1100) {
            RipDpiSettingsMediumPreviewScene()
        }
    }

    @Test
    fun introLargeFontScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900, fontScale = 1.3f) {
            RipDpiIntroLargeFontPreviewScene()
        }
    }

    @Test
    fun onboardingIntroScreen() {
        captureOnboardingScreen(OnboardingUiState(currentPage = 0))
    }

    @Test
    fun onboardingModeSetupScreen() {
        captureOnboardingScreen(
            OnboardingUiState(
                currentPage = OnboardingInfoPageCount,
                selectedMode = Mode.VPN,
            ),
        )
    }

    @Test
    fun onboardingDnsSetupScreen() {
        captureOnboardingScreen(
            uiState =
                OnboardingUiState(
                    currentPage = OnboardingInfoPageCount + 1,
                    selectedMode = Mode.Proxy,
                ),
            heightDp = 1100,
        )
    }

    @Test
    fun onboardingValidationIdleScreen() {
        captureOnboardingValidationScreen(OnboardingValidationState.Idle)
    }

    @Test
    fun onboardingValidationRunningScreen() {
        captureOnboardingValidationScreen(OnboardingValidationState.RunningTrafficCheck(Mode.VPN))
    }

    @Test
    fun onboardingValidationStartingScreen() {
        captureOnboardingValidationScreen(OnboardingValidationState.StartingMode(Mode.Proxy))
    }

    @Test
    fun onboardingValidationRequestingNotificationsScreen() {
        captureOnboardingValidationScreen(OnboardingValidationState.RequestingNotifications)
    }

    @Test
    fun onboardingValidationRequestingVpnConsentScreen() {
        captureOnboardingValidationScreen(OnboardingValidationState.RequestingVpnConsent)
    }

    @Test
    fun onboardingValidationSuccessScreen() {
        captureOnboardingValidationScreen(
            OnboardingValidationState.Success(
                latencyMs = 42,
                mode = Mode.VPN,
            ),
        )
    }

    @Test
    fun onboardingValidationFailureScreen() {
        captureOnboardingValidationScreen(
            OnboardingValidationState.Failed(
                reason = "VPN permission was denied",
                suggestedMode = Mode.Proxy,
            ),
        )
    }

    @Test
    fun homeCompactScreen() {
        captureRipDpiScreenshot(widthDp = 390, heightDp = 800) {
            RipDpiHomeCompactPreviewScene()
        }
    }

    @Test
    fun homeDarkScreen() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 920) {
            RipDpiHomeDarkPreviewScene()
        }
    }

    @Test
    fun settingsDarkScreen() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 1100) {
            RipDpiSettingsDarkPreviewScene()
        }
    }

    @Test
    fun diagnosticsScanScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiDiagnosticsScanPreviewScene()
        }
    }

    @Test
    fun diagnosticsShareScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiDiagnosticsSharePreviewScene()
        }
    }

    @Test
    fun connectionHealthScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiTheme {
                ConnectionHealthScreen(uiState = previewConnectionHealthUiState(), onBack = {})
            }
        }
    }

    @Test
    fun subscriptionFailoverScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiTheme {
                SubscriptionFailoverScreen(uiState = previewSubscriptionFailoverUiState(), onBack = {})
            }
        }
    }

    @Test
    fun strategyTunerScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiTheme {
                StrategyTunerScreen(
                    state = previewStrategyTunerUiState(),
                    onBack = {},
                    onDomainsChanged = {},
                    onRun = {},
                    onCancel = {},
                    onApply = {},
                )
            }
        }
    }

    @Test
    fun homeDisconnectedScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiHomeDisconnectedPreviewScene()
        }
    }

    @Test
    fun homeConnectingScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiHomeConnectingPreviewScene()
        }
    }

    @Test
    fun homeErrorScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiHomeErrorPreviewScene()
        }
    }

    @Test
    fun configScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiConfigPreviewScene()
        }
    }

    @Test
    fun advancedSettingsScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 2400) {
            RipDpiAdvancedSettingsPreviewScene()
        }
    }

    @Test
    fun logsScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiLogsPreviewScene()
        }
    }

    @Test
    fun logsEmptyDarkScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiLogsEmptyDarkPreviewScene()
        }
    }

    @Test
    fun dnsSettingsScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 1200) {
            RipDpiDnsSettingsPreviewScene()
        }
    }

    @Test
    fun aboutScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiAboutPreviewScene()
        }
    }

    @Test
    fun dataTransparencyScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiDataTransparencyPreviewScene()
        }
    }

    @Test
    fun customizationScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiCustomizationPreviewScene()
        }
    }

    @Test
    fun historyScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiHistoryPreviewScene()
        }
    }

    @Test
    fun replayHistoryEmptyScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiTheme {
                ReplayHistoryScreen(
                    replays = persistentListOf(),
                    onRunScan = {},
                )
            }
        }
    }

    @Test
    fun biometricPromptScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiBiometricPromptPreviewScene()
        }
    }

    @Test
    fun biometricPinScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiBiometricPinPreviewScene()
        }
    }

    @Test
    fun vpnPermissionDialog() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 600) {
            RipDpiVpnPermissionDialogPreviewScene()
        }
    }

    // -- New screen coverage ----------------------------------------------------

    @Test
    fun detectionCheckScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiDetectionCheckPreviewScene()
        }
    }

    @Test
    fun detectionCheckDarkScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiDetectionCheckDarkPreviewScene()
        }
    }

    @Test
    fun modeEditorScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 1200) {
            RipDpiModeEditorPreviewScene()
        }
    }

    @Test
    fun modeEditorInvalidChainScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 720) {
            RipDpiModeEditorInvalidChainPreviewScene()
        }
    }

    @Test
    fun strategyConfigScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900) {
            RipDpiStrategyConfigPreviewScene()
        }
    }

    // -- Multi-configuration variants ------------------------------------------

    @Test
    fun homeHighContrastScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 800) {
            RipDpiHomeHighContrastPreviewScene()
        }
    }

    @Test
    fun homeLargeFontScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 900, fontScale = 1.5f) {
            RipDpiHomeCompactPreviewScene()
        }
    }

    @Test
    fun settingsLargeFontScreen() {
        captureRipDpiScreenshot(widthDp = 420, heightDp = 1200, fontScale = 1.5f) {
            RipDpiSettingsMediumPreviewScene()
        }
    }

    @Test
    fun configMediumScreen() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 900) {
            RipDpiConfigPreviewScene()
        }
    }

    @Test
    fun detectionCheckMediumScreen() {
        captureRipDpiScreenshot(widthDp = 720, heightDp = 900) {
            RipDpiDetectionCheckPreviewScene()
        }
    }

    private fun captureOnboardingValidationScreen(validationState: OnboardingValidationState) {
        val validationBusy =
            when (validationState) {
                OnboardingValidationState.Idle,
                is OnboardingValidationState.Success,
                is OnboardingValidationState.Failed,
                -> false

                OnboardingValidationState.RequestingNotifications,
                OnboardingValidationState.RequestingVpnConsent,
                is OnboardingValidationState.StartingMode,
                is OnboardingValidationState.CheckingDns,
                is OnboardingValidationState.RunningTrafficCheck,
                -> true
            }
        captureOnboardingScreen(
            uiState =
                OnboardingUiState(
                    currentPage = OnboardingPages.lastIndex,
                    validationState = validationState,
                    canFinishAnyway = !validationBusy,
                    canFinishKeepingRunning = validationState is OnboardingValidationState.Success,
                    canFinishDisconnected = validationState is OnboardingValidationState.Success,
                ),
        )
    }

    private fun captureOnboardingScreen(
        uiState: OnboardingUiState,
        heightDp: Int = 900,
    ) {
        captureRipDpiScreenshot(widthDp = 420, heightDp = heightDp) {
            OnboardingScreenPreviewScene(uiState)
        }
    }
}

@Composable
private fun OnboardingScreenPreviewScene(uiState: OnboardingUiState) {
    RipDpiTheme(themePreference = "light") {
        OnboardingScreen(
            uiState = uiState,
            actions = OnboardingScreenActions(),
        )
    }
}
