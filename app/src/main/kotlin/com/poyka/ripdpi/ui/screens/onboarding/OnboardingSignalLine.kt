package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalField
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalRipple
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
private const val PreviewControlInset = 160

// Guided is a few wide ripples; Advanced is many fine ones, lower so the line keeps its weight.
private const val GuidedCycles = 2f
private const val GuidedAmplitude = 0.12f
private const val AdvancedCycles = 7f
private const val AdvancedAmplitude = 0.08f

// Proxy only keeps the ripple on the half of the line after the badge.
private const val Half = 0.5f

/**
 * The shape of the onboarding line: its mode, where a failed test breaks it, and the ripple that the persona
 * and the scope give it before the test.
 */
@Immutable
internal data class OnboardingSignal(
    val mode: RipDpiSignalMode,
    val breakAt: Float = DnsStageAt,
    val rippleCycles: Float = GuidedCycles,
    val rippleAmplitude: Float = GuidedAmplitude,
    val rippleOneSided: Boolean = false,
)

/**
 * The mode states the path, so it is flat until the connection test, which is the first step with a path.
 * Away from the test step it stays flat: a failed test must not mark the persona or the scope step as broken.
 * The ripple states the two choices: [persona] sets how fine it is and [scope] sets how much of the line it covers.
 */
internal fun onboardingSignal(
    validationState: OnboardingValidationState,
    onTestStep: Boolean,
    persona: OnboardingPersona,
    scope: Mode,
): OnboardingSignal {
    val advanced = persona == OnboardingPersona.Advanced
    val choices =
        OnboardingSignal(
            mode = RipDpiSignalMode.Idle,
            rippleCycles = if (advanced) AdvancedCycles else GuidedCycles,
            rippleAmplitude = if (advanced) AdvancedAmplitude else GuidedAmplitude,
            rippleOneSided = scope == Mode.Proxy,
        )
    return when {
        !onTestStep || validationState == OnboardingValidationState.Idle -> {
            choices
        }

        validationState is OnboardingValidationState.Success -> {
            choices.copy(mode = RipDpiSignalMode.Live)
        }

        validationState is OnboardingValidationState.Failed -> {
            choices.copy(
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
            choices.copy(mode = RipDpiSignalMode.Seeking)
        }
    }
}

internal val OnboardingPage.isConnectionTest: Boolean
    get() = this is OnboardingPage.Setup && kind == SetupPageKind.ConnectionTest

private fun stepOf(kind: SetupPageKind): Int =
    OnboardingPages.indexOfFirst { it is OnboardingPage.Setup && it.kind == kind }

/**
 * How much of the ripple shows at the pager [position]: it grows over the drag onto [personaStep], stays through
 * the steps after it, and flattens over the drag onto [testStep], where the line states the path alone.
 * A step that the flow does not have is negative, and gives no ripple.
 */
internal fun onboardingRippleStrength(
    position: Float,
    personaStep: Int,
    testStep: Int,
): Float =
    if (personaStep < 0) {
        0f
    } else {
        (position - (personaStep - 1)).coerceIn(0f, 1f) * (testStep - position).coerceIn(0f, 1f)
    }

/** How far the scope shapes the ripple at the pager [position]: not before the drag onto [scopeStep]. */
internal fun onboardingScopeWeight(
    position: Float,
    scopeStep: Int,
): Float = if (scopeStep < 0) 0f else (position - (scopeStep - 1)).coerceIn(0f, 1f)

/** The haptic that reports the result of the connection test, or null when the test has no result. */
internal fun onboardingResultHaptic(validationState: OnboardingValidationState): RipDpiHapticFeedback? =
    when (validationState) {
        is OnboardingValidationState.Success -> RipDpiHapticFeedback.Success
        is OnboardingValidationState.Failed -> RipDpiHapticFeedback.Error
        else -> null
    }

/**
 * Gives one haptic when the connection test passes or fails. A result that is already on the screen when the
 * screen appears was reported before, so a recomposition, a rotation, or a restored process gives none.
 */
@Composable
internal fun OnboardingResultHaptic(validationState: OnboardingValidationState) {
    val result = onboardingResultHaptic(validationState)
    val performHaptic = rememberRipDpiHapticPerformer()
    var reported by rememberSaveable { mutableStateOf(result) }
    LaunchedEffect(result) {
        if (result != reported) {
            reported = result
            result?.let(performHaptic)
        }
    }
}

/**
 * Draws the line only between two controls. [startInset] and [endInset] are the pixels that the control at each
 * end of the row takes, read in the draw phase and mirrored in a right-to-left layout.
 */
internal fun Modifier.onboardingLineBetween(
    startInset: () -> Int,
    endInset: () -> Int,
): Modifier =
    drawWithContent {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val left = (if (rtl) endInset() else startInset()).toFloat()
        val right = size.width - if (rtl) startInset() else endInset()
        if (right > left) {
            clipRect(left = left, right = right) { this@drawWithContent.drawContent() }
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
    val motion = RipDpiThemeTokens.motion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val lastStep = OnboardingPages.lastIndex.toFloat()
    // A new selection reshapes the ripple over the emphasized bucket; with animations off it changes at once.
    val cycles = animateFloatAsState(signal.rippleCycles, motion.emphasizedTween(), label = "Ripple cycles")
    val amplitude = animateFloatAsState(signal.rippleAmplitude, motion.emphasizedTween(), label = "Ripple amplitude")
    val oneSided =
        animateFloatAsState(if (signal.rippleOneSided) 1f else 0f, motion.emphasizedTween(), label = "Ripple side")
    val ripple =
        remember(pagePosition, rtl) {
            val personaStep = stepOf(SetupPageKind.PersonaSelection)
            val scopeStep = stepOf(SetupPageKind.ModeSelection)
            val testStep = stepOf(SetupPageKind.ConnectionTest)
            // The part of the line before the badge that goes flat for Proxy only.
            val flat = { Half * oneSided.value * onboardingScopeWeight(pagePosition(), scopeStep) }
            RipDpiSignalRipple(
                amplitude = { amplitude.value * onboardingRippleStrength(pagePosition(), personaStep, testStep) },
                cycles = { cycles.value },
                start = { if (rtl) 0f else flat() },
                end = { if (rtl) 1f - flat() else 1f },
            )
        }
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
        ripple = ripple,
    )
}

@Composable
private fun OnboardingSignalLinePreviewBody(themePreference: String) {
    RipDpiComponentPreview(themePreference = themePreference) {
        val test = stepOf(SetupPageKind.ConnectionTest).toFloat()
        val scope = stepOf(SetupPageKind.ModeSelection).toFloat()

        fun signal(
            state: OnboardingValidationState,
            persona: OnboardingPersona = OnboardingPersona.Guided,
            mode: Mode = Mode.VPN,
        ) = onboardingSignal(state, onTestStep = state != OnboardingValidationState.Idle, persona, mode)

        val idle = OnboardingValidationState.Idle
        // The first step, and a drag from it to the persona step.
        OnboardingSignalLine(signal(idle), pagePosition = { 0f })
        OnboardingSignalLine(signal(idle), pagePosition = { PreviewBetweenSteps })
        // Each persona with each scope, on the scope step.
        OnboardingPersona.entries.forEach { persona ->
            Mode.entries.forEach { mode ->
                OnboardingSignalLine(signal(idle, persona, mode), pagePosition = { scope })
            }
        }
        // The running test, the passed test, and a failure at each stage.
        OnboardingSignalLine(signal(OnboardingValidationState.CheckingDns(Mode.VPN)), pagePosition = { test })
        OnboardingSignalLine(
            signal(OnboardingValidationState.Success(Mode.VPN, dnsSummary = "")),
            pagePosition = { test },
        )
        OnboardingValidationStep.entries.forEach { step ->
            OnboardingSignalLine(
                signal(OnboardingValidationState.Failed(reason = "", failedStep = step)),
                pagePosition = { test },
            )
        }
        // The landscape top bar: the line stops short of the control at each end.
        OnboardingSignalLine(
            signal(idle, OnboardingPersona.Advanced),
            pagePosition = { scope },
            modifier = Modifier.onboardingLineBetween({ PreviewControlInset }, { PreviewControlInset }),
        )
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
