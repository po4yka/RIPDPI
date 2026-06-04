package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// LocalFirst illustration — a device that contains a local processing engine, with a struck cloud
// up-right ("everything runs on the device; nothing goes to the cloud"). All fractions are of the
// canvas size; the device is portrait and offset left to leave headroom for the no-cloud mark.
private const val localDeviceLeftX = 0.26f
private const val localDeviceRightX = 0.60f
private const val localDeviceTopY = 0.30f
private const val localDeviceBottomY = 0.86f
private const val localDeviceCorner = 14f

// Local engine inside the device — a filled core, an enclosing ring, and two short routing legs.
private const val localEngineCx = 0.43f
private const val localEngineCy = 0.56f
private const val localEngineCoreRadius = 0.045f
private const val localEngineRingRadius = 0.10f
private const val localEngineLegSpread = 0.07f
private const val localEngineLegTopY = 0.68f
private const val localEngineLegBottomY = 0.79f

// No-cloud mark — an outlined cloud, struck through, lifted clear of the device's upper-right corner.
private const val noCloudCx = 0.73f
private const val noCloudCy = 0.28f
private const val noCloudWidth = 0.38f
private const val noCloudHeight = 0.23f
private const val noCloudSlashExtend = 1.18f

// Cloud-silhouette bezier coefficients (fractions of the cloud box) for [cloudPath].
private const val cloudBaseYFactor = 0.34f
private const val cloudTopYFactor = 0.5f
private const val cloudEdgeInset = 0.12f
private const val cloudOuterBulge = 0.02f
private const val cloudShoulderRise = 0.04f
private const val cloudBumpInset = 0.16f
private const val cloudCrownInset = 0.22f

/** The monochrome "private by design" glyph drawn on the intro page. */
@Composable
internal fun OnboardingIllustrationBox(modifier: Modifier = Modifier) {
    val colors = RipDpiThemeTokens.colors
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val strokeWidth = introLayout.illustrationIconStrokeWidth

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke =
                Stroke(
                    width = strokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )

            drawLocalFirstGlyph(colors.foreground, stroke, strokeWidth.toPx())
        }
    }
}

/** Device outline enclosing a local engine, with a struck "no cloud" mark up-right. */
private fun DrawScope.drawLocalFirstGlyph(
    color: Color,
    stroke: Stroke,
    strokeWidthPx: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * localDeviceLeftX, size.height * localDeviceTopY),
        size =
            Size(
                size.width * (localDeviceRightX - localDeviceLeftX),
                size.height * (localDeviceBottomY - localDeviceTopY),
            ),
        cornerRadius = CornerRadius(localDeviceCorner, localDeviceCorner),
        style = stroke,
    )
    drawLocalEngine(color, stroke, strokeWidthPx)
    drawNoCloudMark(color, stroke, strokeWidthPx)
}

/** A filled core ringed by an outline, with two short legs routing down — the on-device engine. */
private fun DrawScope.drawLocalEngine(
    color: Color,
    stroke: Stroke,
    strokeWidthPx: Float,
) {
    val cx = size.width * localEngineCx
    val cy = size.height * localEngineCy
    drawCircle(
        color = color,
        center = Offset(cx, cy),
        radius = size.minDimension * localEngineRingRadius,
        style = stroke,
    )
    drawCircle(color = color, center = Offset(cx, cy), radius = size.minDimension * localEngineCoreRadius)
    val legTop = Offset(cx, size.height * localEngineLegTopY)
    val legBottomY = size.height * localEngineLegBottomY
    drawLine(
        color = color,
        start = legTop,
        end = Offset(cx - size.width * localEngineLegSpread, legBottomY),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = legTop,
        end = Offset(cx + size.width * localEngineLegSpread, legBottomY),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )
}

/** An outlined cloud silhouette struck by a diagonal slash — "nothing leaves for the cloud". */
private fun DrawScope.drawNoCloudMark(
    color: Color,
    stroke: Stroke,
    strokeWidthPx: Float,
) {
    val cx = size.width * noCloudCx
    val cy = size.height * noCloudCy
    val w = size.width * noCloudWidth
    val h = size.height * noCloudHeight
    drawPath(path = cloudPath(cx, cy, w, h), color = color, style = stroke)
    drawLine(
        color = color,
        start = Offset(cx - (w / 2f) * noCloudSlashExtend, cy + (h / 2f) * noCloudSlashExtend),
        end = Offset(cx + (w / 2f) * noCloudSlashExtend, cy - (h / 2f) * noCloudSlashExtend),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )
}

/** A three-bump cloud outline centered at [cx], [cy] within a [w] x [h] box. */
private fun cloudPath(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
): Path {
    val left = cx - w / 2f
    val right = cx + w / 2f
    val baseY = cy + h * cloudBaseYFactor
    val topY = cy - h * cloudTopYFactor
    return Path().apply {
        moveTo(left + w * cloudEdgeInset, baseY)
        cubicTo(
            left - w * cloudOuterBulge,
            baseY,
            left - w * cloudOuterBulge,
            cy + h * cloudShoulderRise,
            left + w * cloudBumpInset,
            cy - h * cloudOuterBulge,
        )
        cubicTo(
            left + w * cloudCrownInset,
            topY,
            right - w * cloudCrownInset,
            topY,
            right - w * cloudBumpInset,
            cy - h * cloudOuterBulge,
        )
        cubicTo(
            right + w * cloudOuterBulge,
            cy + h * cloudShoulderRise,
            right + w * cloudOuterBulge,
            baseY,
            right - w * cloudEdgeInset,
            baseY,
        )
        close()
    }
}
