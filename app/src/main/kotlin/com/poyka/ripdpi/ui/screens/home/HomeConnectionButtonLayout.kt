package com.poyka.ripdpi.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val modeLabelAlpha = 0.72f
private const val haloTranslationFraction = 0.2f
private const val iconTransitionScale = 0.88f
private const val connectionButtonIconSpacerDp = 12
private const val connectionButtonModeSpacerDp = 6

@Composable
internal fun HomeConnectionButtonLayout(
    state: ConnectionState,
    label: String,
    modeLabel: String,
    stateDescription: String,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
    visuals: HomeConnectionButtonVisuals,
    motionState: HomeConnectionButtonMotionState,
) {
    val motion = RipDpiThemeTokens.motion
    val homeChrome = rememberHomeChromeMetrics()

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(homeChrome.connectionHaloSize)
                    .graphicsLayer {
                        scaleX = motionState.haloScale.value
                        scaleY = motionState.haloScale.value
                        alpha = motionState.connectingHaloAlpha
                        translationX = motionState.shakeOffset.value * haloTranslationFraction
                    }.background(visuals.haloColor, CircleShape),
        )
        Column(
            modifier =
                Modifier
                    .ripDpiTestTag(RipDpiTestTags.ConnectionActuatorButton)
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        this.stateDescription = stateDescription
                        liveRegion = LiveRegionMode.Polite
                    }.size(homeChrome.connectionButtonSize)
                    .graphicsLayer {
                        scaleX = motionState.buttonScale.value * motionState.pressScale
                        scaleY = motionState.buttonScale.value * motionState.pressScale
                        translationX = motionState.shakeOffset.value
                    }.background(visuals.containerColor, CircleShape)
                    .border(
                        width = 1.dp,
                        color = visuals.borderColor,
                        shape = CircleShape,
                    ).clip(CircleShape)
                    .ripDpiClickable(
                        enabled = state != ConnectionState.Connecting,
                        role = Role.Button,
                        interactionSource = interactionSource,
                        hapticFeedback = state.connectionButtonClickHaptic(),
                        onClick = onClick,
                    ).padding(
                        horizontal = homeChrome.connectionHorizontalPadding,
                        vertical = homeChrome.connectionVerticalPadding,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            HomeConnectionButtonContent(
                label = label,
                modeLabel = modeLabel,
                visuals = visuals,
            )
        }
    }
}

@Composable
private fun HomeConnectionButtonContent(
    label: String,
    modeLabel: String,
    visuals: HomeConnectionButtonVisuals,
) {
    val motion = RipDpiThemeTokens.motion
    val type = RipDpiThemeTokens.type

    AnimatedContent(
        targetState = visuals.icon,
        transitionSpec = {
            motion.quickContentTransform(
                initialScale = iconTransitionScale,
                targetScale = iconTransitionScale,
            )
        },
        label = "homeConnectionIcon",
    ) { currentIcon ->
        Icon(
            imageVector = currentIcon,
            contentDescription = null,
            tint = visuals.contentColor,
            modifier = Modifier.size(rememberHomeChromeMetrics().connectionIconSize),
        )
    }
    Spacer(modifier = Modifier.height(connectionButtonIconSpacerDp.dp))
    AnimatedContent(
        targetState = label,
        transitionSpec = {
            fadeIn(animationSpec = motion.stateTween()) togetherWith
                fadeOut(animationSpec = motion.quickTween())
        },
        label = "homeConnectionLabel",
    ) { currentLabel ->
        Text(
            text = currentLabel,
            style = type.bodyEmphasis,
            color = visuals.contentColor,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(modifier = Modifier.height(connectionButtonModeSpacerDp.dp))
    AnimatedContent(
        targetState = modeLabel,
        transitionSpec = {
            fadeIn(animationSpec = motion.quickTween()) togetherWith
                fadeOut(animationSpec = motion.quickTween())
        },
        label = "homeConnectionModeLabel",
    ) { currentModeLabel ->
        Text(
            text = currentModeLabel,
            style = type.caption,
            color = visuals.contentColor.copy(alpha = modeLabelAlpha),
            textAlign = TextAlign.Center,
        )
    }
}

private fun ConnectionState.connectionButtonClickHaptic(): RipDpiHapticFeedback =
    when (this) {
        ConnectionState.Connected -> RipDpiHapticFeedback.Toggle
        ConnectionState.Connecting -> RipDpiHapticFeedback.None
        ConnectionState.Disconnected, ConnectionState.Error -> RipDpiHapticFeedback.Action
    }
