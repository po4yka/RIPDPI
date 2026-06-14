package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.min

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
    val loadingDescription = stringResource(R.string.cd_loading)
    val spinnerModifier =
        modifier
            .size(size.dp)
            .semantics { contentDescription = loadingDescription }
    val foreground = RipDpiThemeTokens.colors.foreground
    val track = RipDpiThemeTokens.colors.muted
    val stroke = size.stroke

    if (LocalInspectionMode.current || !RipDpiThemeTokens.motion.allowsInfiniteMotion) {
        Canvas(modifier = spinnerModifier) {
            val strokeWidth = stroke.toPx()
            val radius = (min(this.size.width, this.size.height) - strokeWidth) / 2f
            drawCircle(
                color = track,
                radius = radius,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = foreground,
                startAngle = StaticSpinnerStartAngle,
                sweepAngle = StaticSpinnerSweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        return
    }

    CircularProgressIndicator(
        modifier = spinnerModifier,
        color = foreground,
        trackColor = track,
        strokeWidth = stroke,
    )
}

private const val StaticSpinnerStartAngle = -90f
private const val StaticSpinnerSweepAngle = 260f

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
