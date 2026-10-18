package com.poyka.ripdpi.ui.screens.health

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun LatencyDetailDialog(
    snapshot: LatencyDetailSnapshot,
    onDismiss: () -> Unit,
) {
    val motion = RipDpiThemeTokens.motion
    val allowsMotion = motion.animationsEnabled && !motion.reducedMotion && !LocalInspectionMode.current
    var dismissRequested by remember(snapshot) { mutableStateOf(false) }
    var hasPresented by remember(snapshot) { mutableStateOf(false) }
    val opacity = remember(snapshot, allowsMotion) { Animatable(if (allowsMotion && !hasPresented) 0f else 1f) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    SideEffect { hasPresented = true }
    LaunchedEffect(dismissRequested, allowsMotion) {
        val target = if (dismissRequested) 0f else 1f
        if (allowsMotion) opacity.animateTo(target, motion.quickTween()) else opacity.snapTo(target)
        if (dismissRequested) currentOnDismiss()
    }
    // Policy changes take effect in this frame, even if an entrance or exit
    // animation was in flight. There is no second Material or window animation.
    if (dismissRequested && !allowsMotion) return
    val title = stringResource(R.string.latency_detail_title)
    val layout = RipDpiThemeTokens.layout
    val requestDismiss = { dismissRequested = true }
    Dialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setWindowAnimations(0) }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiAutomationTreeRoot()
                    .padding(horizontal = layout.horizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            RipDpiDialogCard(
                title = title,
                dismissAction =
                    RipDpiDialogAction(
                        stringResource(R.string.action_dismiss),
                        requestDismiss,
                        RipDpiTestTags.LatencyDetailDismiss,
                    ),
                modifier =
                    Modifier
                        .widthIn(max = layout.dialogMaxWidth)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = if (allowsMotion) opacity.value else 1f }
                        .ripDpiTestTag(RipDpiTestTags.LatencyDetailDialog)
                        .semantics { paneTitle = title },
                visuals = RipDpiDialogVisuals(message = stringResource(R.string.latency_detail_snapshot_note)),
            ) {
                LatencyDetailContent(snapshot)
            }
        }
    }
}
