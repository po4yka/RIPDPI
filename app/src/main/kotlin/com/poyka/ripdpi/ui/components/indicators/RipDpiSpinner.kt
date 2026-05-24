package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiSpinnerSize(
    val dp: Dp,
    val stroke: Dp,
) {
    Small(16.dp, 1.5.dp),
    Standard(24.dp, 2.dp),
    Large(40.dp, 3.dp),
}

/**
 * Branded indeterminate circular progress. Wraps Material3
 * [CircularProgressIndicator] with the design system foreground color
 * and a closed scale of three canonical sizes.
 *
 * Matches `components-spinner.html`.
 */
@Composable
fun RipDpiSpinner(
    modifier: Modifier = Modifier,
    size: RipDpiSpinnerSize = RipDpiSpinnerSize.Standard,
) {
    CircularProgressIndicator(
        modifier =
            modifier
                .size(size.dp)
                .semantics { contentDescription = "Loading" },
        color = RipDpiThemeTokens.colors.foreground,
        trackColor = RipDpiThemeTokens.colors.muted,
        strokeWidth = size.stroke,
    )
}

@Preview(showBackground = true, name = "RipDpiSpinner (light)")
@Composable
private fun RipDpiSpinnerLightPreview() {
    RipDpiComponentPreview {
        Row(
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.lg),
        ) {
            RipDpiSpinner(size = RipDpiSpinnerSize.Small)
            RipDpiSpinner(size = RipDpiSpinnerSize.Standard)
            RipDpiSpinner(size = RipDpiSpinnerSize.Large)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiSpinner (dark)")
@Composable
private fun RipDpiSpinnerDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.lg),
        ) {
            RipDpiSpinner(size = RipDpiSpinnerSize.Standard)
            RipDpiSpinner(size = RipDpiSpinnerSize.Large)
        }
    }
}
