package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Gallery composable that renders the production
 * [RipDpiConnectionActuator] in each canonical state for spec-card
 * documentation and visual regression. Not intended for production
 * surfaces — those use the actuator directly with live state.
 *
 * Matches `components-actuator-states.html`.
 */
@Composable
fun RipDpiActuatorStatesGallery(modifier: Modifier = Modifier) {
    val rows =
        listOf(
            "Open" to HomeConnectionActuatorStatus.Open,
            "Engaging" to HomeConnectionActuatorStatus.Engaging,
            "Locked" to HomeConnectionActuatorStatus.Locked,
            "Degraded" to HomeConnectionActuatorStatus.Degraded,
            "Fault" to HomeConnectionActuatorStatus.Fault,
        )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.lg)) {
        rows.forEach { (label, status) ->
            Text(
                text = label,
                style = RipDpiThemeTokens.type.caption.copy(color = RipDpiThemeTokens.colors.mutedForeground),
            )
            RipDpiConnectionActuator(
                state =
                    HomeConnectionActuatorUiState(
                        status = status,
                        leadingLabel = label,
                        trailingLabel = label,
                        statusDescription = "$label state",
                        actionLabel = "$label action",
                    ),
                onActivate = {},
                onDeactivate = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "RipDpiActuatorStatesGallery (light)")
@Composable
private fun RipDpiActuatorStatesGalleryLightPreview() {
    RipDpiComponentPreview {
        RipDpiActuatorStatesGallery()
    }
}

@Preview(showBackground = true, name = "RipDpiActuatorStatesGallery (dark)")
@Composable
private fun RipDpiActuatorStatesGalleryDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiActuatorStatesGallery()
    }
}
