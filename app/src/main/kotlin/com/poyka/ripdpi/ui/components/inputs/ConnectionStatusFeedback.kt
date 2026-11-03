package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinnerSize
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** Finite visual feedback; the adjacent current status owns spoken announcements. */
@Composable
internal fun ConnectionStatusFeedback(
    status: StatusConnectionActuatorStatus,
    tint: Color,
) {
    val motion = RipDpiThemeTokens.motion
    val slotSize = maxOf(RipDpiThemeTokens.components.indicators.spinnerSmallSize, RipDpiIconSizes.Small)
    Box(
        modifier = Modifier.size(slotSize).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (!motion.animationsEnabled) {
            ConnectionStatusSymbol(status, tint)
        } else {
            AnimatedContent(
                targetState = status,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
                transitionSpec = { motion.quickFadeContentTransform().using(null) },
                label = "Connection status feedback",
            ) { visibleStatus ->
                ConnectionStatusSymbol(visibleStatus, tint)
            }
        }
    }
}

@Composable
private fun ConnectionStatusSymbol(
    status: StatusConnectionActuatorStatus,
    tint: Color,
) {
    if (status == StatusConnectionActuatorStatus.Engaging) {
        // The service has no measured progress. Keep cancellation on the separate button.
        RipDpiSpinner(size = RipDpiSpinnerSize.Small)
    } else {
        Icon(
            imageVector = status.icon(),
            contentDescription = null,
            modifier = Modifier.size(RipDpiIconSizes.Small),
            tint = tint,
        )
    }
}

private fun StatusConnectionActuatorStatus.icon() =
    when (this) {
        StatusConnectionActuatorStatus.Open -> RipDpiIcons.Offline
        StatusConnectionActuatorStatus.Engaging -> RipDpiIcons.Vpn
        StatusConnectionActuatorStatus.Locked -> RipDpiIcons.Check
        StatusConnectionActuatorStatus.Degraded -> RipDpiIcons.Warning
        StatusConnectionActuatorStatus.Fault -> RipDpiIcons.Error
    }
