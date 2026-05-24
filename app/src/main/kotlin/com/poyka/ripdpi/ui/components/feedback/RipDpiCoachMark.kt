package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** Anchor for a [RipDpiCoachMark] — circular spotlight cut into the scrim. */
data class RipDpiCoachMarkAnchor(
    val center: Offset,
    val radius: Dp = 48.dp,
)

/** Text + action contents of a [RipDpiCoachMark] bubble. */
data class RipDpiCoachMarkContent(
    val title: String,
    val body: String,
    val primaryActionLabel: String? = null,
    val onPrimaryAction: (() -> Unit)? = null,
    val secondaryActionLabel: String? = null,
    val onSecondaryAction: (() -> Unit)? = null,
)

private const val RING_PULSE_TARGET_SCALE = 1.18f
private const val BUBBLE_GAP_DP = 24

/**
 * Onboarding coach-mark: a full-screen scrim with a circular cutout
 * over [anchor], a pulsing spotlight ring around the cutout, and a
 * bubble below carrying [content]. Tapping the scrim or the bubble's
 * primary action dismisses.
 *
 * The pulse animation is driven by `RipDpiMotion.connectRingSpec()`
 * and respects `motion.allowsInfiniteMotion`: when the user has
 * reduced motion enabled (or animations are disabled system-wide),
 * the infinite transition is not subscribed and the ring renders
 * statically at its initial scale + alpha — per the brief's
 * "reduced-motion path skipping auto-advance" requirement.
 *
 * Matches `onboarding-coach-mark.html`.
 */
@Composable
fun RipDpiCoachMark(
    visible: Boolean,
    anchor: RipDpiCoachMarkAnchor,
    content: RipDpiCoachMarkContent,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val density = LocalDensity.current

    // Only subscribe to the infinite transition when motion is enabled —
    // otherwise the ring renders statically at its initial scale + alpha,
    // which matches the brief's reduced-motion contract and keeps the
    // Roborazzi-captured frame deterministic.
    val pulseScale: Float
    val pulseAlpha: Float
    if (motion.allowsInfiniteMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "coachMarkPulse")
        pulseScale =
            infiniteTransition
                .animateFloat(
                    initialValue = 1f,
                    targetValue = RING_PULSE_TARGET_SCALE,
                    animationSpec = motion.connectRingSpec(),
                    label = "coachMarkRingScale",
                ).value
        pulseAlpha =
            infiniteTransition
                .animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = motion.connectRingSpec(),
                    label = "coachMarkRingAlpha",
                ).value
    } else {
        pulseScale = 1f
        pulseAlpha = 1f
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Box(modifier = modifier.fillMaxSize().ripDpiClickable(onClickLabel = "Dismiss", onClick = onDismiss)) {
            // Scrim with circular cutout at anchor.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = Color.Black.copy(alpha = 0.55f))
                drawCircle(
                    color = Color.Transparent,
                    radius = with(density) { anchor.radius.toPx() },
                    center = anchor.center,
                    blendMode = BlendMode.Clear,
                )
                // Pulsing spotlight ring.
                val ringRadius = with(density) { (anchor.radius + 8.dp).toPx() } * pulseScale
                drawCircle(
                    color = colors.foreground.copy(alpha = pulseAlpha),
                    radius = ringRadius,
                    center = anchor.center,
                    style = Stroke(width = with(density) { 2.dp.toPx() }),
                )
            }
            CoachMarkBubble(
                content = content,
                onDismiss = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val xPx = (anchor.center.x - placeable.width / 2f).toInt()
                            val yPx =
                                (
                                    anchor.center.y +
                                        with(density) { (anchor.radius + BUBBLE_GAP_DP.dp).toPx() }
                                ).toInt()
                            layout(placeable.width, placeable.height) {
                                placeable.place(xPx.coerceAtLeast(0), yPx.coerceAtLeast(0))
                            }
                        },
            )
        }
    }
}

@Composable
private fun CoachMarkBubble(
    content: RipDpiCoachMarkContent,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier =
            modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(RipDpiThemeTokens.spacing.md))
                .background(colors.card)
                .padding(RipDpiThemeTokens.spacing.md)
                .semantics { contentDescription = "Coach mark: ${content.title}. ${content.body}" },
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        Text(content.title, style = RipDpiThemeTokens.type.bodyEmphasis.copy(color = colors.foreground))
        Text(
            content.body,
            style = RipDpiThemeTokens.type.secondaryBody.copy(color = colors.mutedForeground),
        )
        if (content.primaryActionLabel != null || content.secondaryActionLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                content.secondaryActionLabel?.let { label ->
                    RipDpiButton(
                        text = label,
                        onClick = {
                            content.onSecondaryAction?.invoke()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                content.primaryActionLabel?.let { label ->
                    RipDpiButton(
                        text = label,
                        onClick = {
                            content.onPrimaryAction?.invoke()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiCoachMark bubble (light)")
@Composable
private fun RipDpiCoachMarkBubblePreviewLight() {
    RipDpiComponentPreview {
        CoachMarkBubble(
            content =
                RipDpiCoachMarkContent(
                    title = "Tap to connect",
                    body = "The actuator is your single tap to engage or release the tunnel.",
                    primaryActionLabel = "Got it",
                    onPrimaryAction = {},
                    secondaryActionLabel = "Show all tips",
                    onSecondaryAction = {},
                ),
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, name = "RipDpiCoachMark bubble (dark)")
@Composable
private fun RipDpiCoachMarkBubblePreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        CoachMarkBubble(
            content =
                RipDpiCoachMarkContent(
                    title = "Strategy details",
                    body = "tlsrec_split_host splits the ClientHello after the SNI extension.",
                ),
            onDismiss = {},
        )
    }
}
