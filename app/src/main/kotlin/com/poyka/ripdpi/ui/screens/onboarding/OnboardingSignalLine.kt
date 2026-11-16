package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalField
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.abs
import kotlin.math.floor

// The three test stages sit at the quarter points of the line, in the order of the checklist.
private const val TunnelStageAt = 0.25f
private const val DnsStageAt = 0.5f
private const val ConnectivityStageAt = 0.75f

// The dip travels between these fractions of the width, so it stays clear of the faded ends.
private const val DipStart = 0.15f
private const val DipEnd = 0.85f
private const val PreviewBetweenSteps = 0.5f

/** The shape of the onboarding line: its mode and, for a failed test, where it breaks. */
@Immutable
internal data class OnboardingSignal(
    val mode: RipDpiSignalMode,
    val breakAt: Float = DnsStageAt,
)

/**
 * The line states the path, so it is flat until the connection test, which is the first step with a path.
 * Away from the test step it stays flat: a failed test must not mark the persona or the scope step as broken.
 */
internal fun onboardingSignal(
    validationState: OnboardingValidationState,
    onTestStep: Boolean,
): OnboardingSignal =
    when {
        !onTestStep || validationState == OnboardingValidationState.Idle -> {
            OnboardingSignal(RipDpiSignalMode.Idle)
        }

        validationState is OnboardingValidationState.Success -> {
            OnboardingSignal(RipDpiSignalMode.Live)
        }

        validationState is OnboardingValidationState.Failed -> {
            OnboardingSignal(
                mode = RipDpiSignalMode.Broken,
                breakAt =
                    when (validationState.failedStep) {
                        OnboardingValidationStep.Tunnel -> TunnelStageAt
                        OnboardingValidationStep.Dns, null -> DnsStageAt
                        OnboardingValidationStep.Connectivity -> ConnectivityStageAt
                    },
            )
        }

        else -> {
            OnboardingSignal(RipDpiSignalMode.Seeking)
        }
    }

/**
 * The main-screen signal line, carried through onboarding. During a drag between two steps the line dips at a
 * point that travels with [pagePosition], which is read in the draw phase.
 */
@Composable
internal fun OnboardingSignalLine(
    signal: OnboardingSignal,
    pagePosition: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val lastStep = OnboardingPages.lastIndex.toFloat()
    RipDpiSignalField(
        mode = signal.mode,
        color =
            when (signal.mode) {
                RipDpiSignalMode.Idle -> colors.outline
                RipDpiSignalMode.Broken -> colors.destructive
                else -> colors.signal
            },
        modifier = modifier,
        breakAt = if (rtl) 1f - signal.breakAt else signal.breakAt,
        pressAt = {
            val along = DipStart + (DipEnd - DipStart) * (pagePosition() / lastStep).coerceIn(0f, 1f)
            if (rtl) 1f - along else along
        },
        // Deepest halfway between two steps, none at rest.
        press = {
            val position = pagePosition()
            1f - abs(2f * (position - floor(position)) - 1f)
        },
    )
}

@Composable
private fun OnboardingSignalLinePreviewBody(themePreference: String) {
    RipDpiComponentPreview(themePreference = themePreference) {
        val test = OnboardingPages.lastIndex.toFloat()
        // Before the test, a drag between two steps, the running test, the passed test, and a failure at each stage.
        OnboardingSignalLine(OnboardingSignal(RipDpiSignalMode.Idle), pagePosition = { 0f })
        OnboardingSignalLine(OnboardingSignal(RipDpiSignalMode.Idle), pagePosition = { PreviewBetweenSteps })
        OnboardingSignalLine(
            onboardingSignal(OnboardingValidationState.CheckingDns(Mode.VPN), onTestStep = true),
            pagePosition = { test },
        )
        OnboardingSignalLine(
            onboardingSignal(OnboardingValidationState.Success(Mode.VPN, dnsSummary = ""), onTestStep = true),
            pagePosition = { test },
        )
        OnboardingValidationStep.entries.forEach { step ->
            OnboardingSignalLine(
                onboardingSignal(OnboardingValidationState.Failed(reason = "", failedStep = step), onTestStep = true),
                pagePosition = { test },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingSignalLinePreview() {
    OnboardingSignalLinePreviewBody(themePreference = "light")
}

@Preview(showBackground = true)
@Composable
private fun OnboardingSignalLineDarkPreview() {
    OnboardingSignalLinePreviewBody(themePreference = "dark")
}
