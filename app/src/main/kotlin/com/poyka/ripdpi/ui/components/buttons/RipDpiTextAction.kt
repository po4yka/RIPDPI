package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Low-emphasis text-only action: no container, no border, no button padding.
 *
 * [RipDpiButton] owns the cases that need button chrome; this owns the cases that read as a
 * link -- "Skip", "Finish anyway", inline dismissals. Both route through `ripDpiClickable`, so
 * haptics, focus and the minimum touch target stay identical.
 *
 * The caller supplies [textStyle] and [color] because a text action inherits the tone of the
 * surface it sits on rather than carrying a variant palette of its own.
 */
@Composable
fun RipDpiTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle? = null,
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
    minHeight: Dp? = null,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Action,
    contentAlignment: Alignment = Alignment.Center,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val resolvedColor = if (color == Color.Unspecified) colors.mutedForeground else color
    val sized = if (minHeight != null) modifier.heightIn(min = minHeight) else modifier
    Box(
        modifier =
            sized.ripDpiClickable(
                enabled = enabled,
                role = Role.Button,
                hapticFeedback = hapticFeedback,
                onClick = onClick,
            ),
        contentAlignment = contentAlignment,
    ) {
        Text(
            text = text,
            style = textStyle ?: type.button,
            color = if (enabled) resolvedColor else colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true, name = "RipDpiTextAction (light)")
@Composable
private fun RipDpiTextActionLightPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiTextAction(text = "Skip", onClick = {})
            RipDpiTextAction(text = "Finish anyway", onClick = {}, color = RipDpiThemeTokens.colors.foreground)
            RipDpiTextAction(text = "Disabled", onClick = {}, enabled = false)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiTextAction (dark)")
@Composable
private fun RipDpiTextActionDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiTextAction(text = "Skip", onClick = {})
            RipDpiTextAction(text = "Disabled", onClick = {}, enabled = false)
        }
    }
}
