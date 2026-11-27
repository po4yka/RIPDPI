package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.theme.RipDpiTheme

private const val PreviewFailureReason = "The network did not answer."

/** Every step of the flow in every state it can show. */
private class OnboardingStateProvider : PreviewParameterProvider<OnboardingUiState> {
    private val persona = onboardingPageIndex(SetupPageKind.PersonaSelection)
    private val scope = onboardingPageIndex(SetupPageKind.ModeSelection)
    private val dns = onboardingPageIndex(SetupPageKind.DnsSelection)
    private val background = onboardingPageIndex(SetupPageKind.BackgroundGuidance)
    private val test = OnboardingTestPageIndex
    private val completion = OnboardingCompletionPageIndex

    private fun testState(
        validationState: OnboardingValidationState,
        mode: Mode = Mode.VPN,
        notificationsNoteVisible: Boolean = false,
    ) = OnboardingUiState(
        currentPage = test,
        selectedMode = mode,
        notificationsNoteVisible = notificationsNoteVisible,
    ).withValidationState(validationState)

    private fun completionState(
        validationState: OnboardingValidationState,
        mode: Mode = Mode.VPN,
    ) = OnboardingUiState(currentPage = completion, selectedMode = mode).withValidationState(validationState)

    override val values: Sequence<OnboardingUiState> =
        sequenceOf(
            // Welcome, then the privacy step.
            OnboardingUiState(currentPage = 0),
            OnboardingUiState(currentPage = 1),
            OnboardingUiState(currentPage = persona, selectedPersona = "simple"),
            OnboardingUiState(currentPage = persona, selectedPersona = "advanced"),
            OnboardingUiState(currentPage = scope, selectedMode = Mode.VPN),
            OnboardingUiState(currentPage = scope, selectedMode = Mode.Proxy),
            OnboardingUiState(currentPage = dns),
            OnboardingUiState(currentPage = dns, selectedDnsProviderId = DnsProviderCloudflare),
            OnboardingUiState(currentPage = background, selectedMode = Mode.VPN),
            OnboardingUiState(currentPage = background, selectedMode = Mode.Proxy),
            testState(OnboardingValidationState.Idle),
            testState(OnboardingValidationState.Idle, notificationsNoteVisible = true),
            testState(OnboardingValidationState.Idle, mode = Mode.Proxy),
            testState(OnboardingValidationState.Idle, mode = Mode.Proxy, notificationsNoteVisible = true),
            testState(OnboardingValidationState.RequestingNotifications),
            testState(OnboardingValidationState.RequestingVpnConsent),
            testState(OnboardingValidationState.StartingMode(Mode.VPN)),
            testState(OnboardingValidationState.CheckingDns(Mode.VPN)),
            testState(OnboardingValidationState.RunningTrafficCheck(Mode.VPN)),
            testState(
                OnboardingValidationState.Success(
                    mode = Mode.VPN,
                    dnsSummary = "System DNS",
                    volumeTested = true,
                ),
            ),
            testState(
                OnboardingValidationState.Failed(
                    reason = PreviewFailureReason,
                    suggestedMode = Mode.Proxy,
                    failedStep = OnboardingValidationStep.Connectivity,
                ),
            ),
            testState(
                OnboardingValidationState.Failed(
                    reason = PreviewFailureReason,
                    failedStep = OnboardingValidationStep.Dns,
                ),
            ),
            testState(
                OnboardingValidationState.Failed(
                    reason = PreviewFailureReason,
                    recoveryKind = OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION,
                    suggestedMode = Mode.Proxy,
                    failedStep = OnboardingValidationStep.Tunnel,
                ),
            ),
            testState(
                OnboardingValidationState.Failed(
                    reason = PreviewFailureReason,
                    recoveryKind = OnboardingValidationRecoveryKind.REQUEST_NOTIFICATIONS,
                    failedStep = OnboardingValidationStep.Tunnel,
                ),
            ),
            // The completion step for each exit: a skipped test, a passed test, and a failed test.
            completionState(OnboardingValidationState.Idle),
            completionState(OnboardingValidationState.Idle, mode = Mode.Proxy),
            completionState(
                OnboardingValidationState.Success(
                    mode = Mode.VPN,
                    dnsSummary = "System DNS",
                    volumeTested = true,
                ),
            ),
            completionState(
                OnboardingValidationState.Failed(
                    reason = PreviewFailureReason,
                    failedStep = OnboardingValidationStep.Connectivity,
                ),
            ),
        )
}

@Composable
private fun OnboardingPreviewBody(
    uiState: OnboardingUiState,
    themePreference: String,
) {
    RipDpiTheme(themePreference = themePreference) {
        OnboardingScreen(uiState = uiState, actions = OnboardingScreenActions())
    }
}

@Preview(name = "Phone, light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingPhoneLightPreview(
    @PreviewParameter(OnboardingStateProvider::class) uiState: OnboardingUiState,
) {
    OnboardingPreviewBody(uiState, themePreference = "light")
}

@Preview(name = "Phone, dark", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingPhoneDarkPreview(
    @PreviewParameter(OnboardingStateProvider::class) uiState: OnboardingUiState,
) {
    OnboardingPreviewBody(uiState, themePreference = "dark")
}

@Preview(name = "Landscape, light", showBackground = true, widthDp = 800, heightDp = 360)
@Composable
private fun OnboardingLandscapeLightPreview(
    @PreviewParameter(OnboardingStateProvider::class) uiState: OnboardingUiState,
) {
    OnboardingPreviewBody(uiState, themePreference = "light")
}

@Preview(name = "Landscape, dark", showBackground = true, widthDp = 800, heightDp = 360)
@Composable
private fun OnboardingLandscapeDarkPreview(
    @PreviewParameter(OnboardingStateProvider::class) uiState: OnboardingUiState,
) {
    OnboardingPreviewBody(uiState, themePreference = "dark")
}

@Preview(name = "Tablet, light", showBackground = true, widthDp = 840, heightDp = 1100)
@Composable
private fun OnboardingTabletLightPreview(
    @PreviewParameter(OnboardingStateProvider::class) uiState: OnboardingUiState,
) {
    OnboardingPreviewBody(uiState, themePreference = "light")
}

@Preview(name = "Tablet, dark", showBackground = true, widthDp = 840, heightDp = 1100)
@Composable
private fun OnboardingTabletDarkPreview(
    @PreviewParameter(OnboardingStateProvider::class) uiState: OnboardingUiState,
) {
    OnboardingPreviewBody(uiState, themePreference = "dark")
}

@Preview(name = "Font 200%, light", showBackground = true, widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun OnboardingLargeFontLightPreview(
    @PreviewParameter(OnboardingStateProvider::class) uiState: OnboardingUiState,
) {
    OnboardingPreviewBody(uiState, themePreference = "light")
}

@Preview(name = "Font 200%, dark", showBackground = true, widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun OnboardingLargeFontDarkPreview(
    @PreviewParameter(OnboardingStateProvider::class) uiState: OnboardingUiState,
) {
    OnboardingPreviewBody(uiState, themePreference = "dark")
}
