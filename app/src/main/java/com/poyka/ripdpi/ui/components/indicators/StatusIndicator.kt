package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class StatusIndicatorTone {
    Active,
    Idle,
    Warning,
    Error,
}

@Composable
fun StatusIndicator(
    label: String,
    modifier: Modifier = Modifier,
    tone: StatusIndicatorTone = StatusIndicatorTone.Active,
) {
    val colors = RipDpiThemeTokens.colors
    val indicatorColor =
        when (tone) {
            StatusIndicatorTone.Active -> colors.foreground
            StatusIndicatorTone.Idle -> colors.mutedForeground
            StatusIndicatorTone.Warning -> colors.warning
            StatusIndicatorTone.Error -> colors.destructive
        }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(indicatorColor, CircleShape),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {}
        Text(
            text = label,
            style = RipDpiThemeTokens.type.brandStatus,
            color = colors.foreground,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusIndicatorPreview() {
    RipDpiComponentPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusIndicator(label = "Running")
            StatusIndicator(label = "Idle", tone = StatusIndicatorTone.Idle)
            StatusIndicator(label = "Warning", tone = StatusIndicatorTone.Warning)
            StatusIndicator(label = "Error", tone = StatusIndicatorTone.Error)
        }
    }
}
