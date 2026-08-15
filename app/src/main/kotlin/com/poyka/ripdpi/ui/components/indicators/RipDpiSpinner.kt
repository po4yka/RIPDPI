package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.min

enum class RipDpiSpinnerSize {
    Small,
    Standard,
    Large,
}

private data class RipDpiSpinnerDimensions(
    val size: Dp,
    val stroke: Dp,
)

@Composable
private fun RipDpiSpinnerSize.dimensions(): RipDpiSpinnerDimensions {
    val indicators = RipDpiThemeTokens.components.indicators
    return when (this) {
        RipDpiSpinnerSize.Small -> {
            RipDpiSpinnerDimensions(indicators.spinnerSmallSize, indicators.spinnerSmallStroke)
        }

        RipDpiSpinnerSize.Standard -> {
            RipDpiSpinnerDimensions(indicators.spinnerStandardSize, indicators.spinnerStandardStroke)
        }

        RipDpiSpinnerSize.Large -> {
            RipDpiSpinnerDimensions(indicators.spinnerLargeSize, indicators.spinnerLargeStroke)
        }
    }
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
    val dimensions = size.dimensions()
    val spinnerModifier =
        modifier
            .size(dimensions.size)
            .semantics {
                contentDescription = loadingDescription
                liveRegion = LiveRegionMode.Polite
            }
    val foreground = RipDpiThemeTokens.colors.foreground
    val track = RipDpiThemeTokens.colors.muted
    val stroke = dimensions.stroke

    if (LocalInspectionMode.current || !RipDpiThemeTokens.motion.allowsInfiniteMotion) {
        RipDpiStaticCircularProgressIndicator(
            modifier = spinnerModifier,
            color = foreground,
            trackColor = track,
            strokeWidth = stroke,
        )
        return
    }

    CircularProgressIndicator(
        modifier = spinnerModifier,
        color = foreground,
        trackColor = track,
        strokeWidth = stroke,
    )
}

/** Deterministic counterpart to Material's indeterminate spinner for static/reduced-motion renders. */
@Composable
internal fun RipDpiStaticCircularProgressIndicator(
    color: Color,
    trackColor: Color,
    strokeWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.progressSemantics()) {
        val strokeWidthPx = strokeWidth.toPx()
        val radius = (min(size.width, size.height) - strokeWidthPx) / 2f
        drawCircle(
            color = trackColor,
            radius = radius,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = StaticSpinnerStartAngle,
            sweepAngle = StaticSpinnerSweepAngle,
            useCenter = false,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
        )
    }
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
