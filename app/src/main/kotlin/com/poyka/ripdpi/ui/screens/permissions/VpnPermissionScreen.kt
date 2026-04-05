package com.poyka.ripdpi.ui.screens.permissions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.scaffold.RipDpiIntroScaffold
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// Corner radius for pill-shaped rect (large value → fully rounded)
private const val cornerRadiusPill = 99f

// Shield path fractions (Permission illustration)
private const val shieldCenterX = 0.5f
private const val shieldTopY = 0.12f
private const val shieldRightX = 0.78f
private const val shieldShoulderY = 0.22f
private const val shieldWaistY = 0.48f
private const val shieldCurveY = 0.72f
private const val shieldTipInnerX = 0.62f
private const val shieldTipY = 0.86f
private const val shieldBottomY = 0.92f
private const val shieldLeftX = 0.22f
private const val shieldLeftInnerX = 0.38f

// Biometric illustration fractions
private const val bioArcLeftX = 0.22f
private const val bioArcTopY = 0.08f
private const val bioArcWidth = 0.56f
private const val bioArcHeight = 0.54f
private const val bioBodyLeftX = 0.24f
private const val bioBodyTopY = 0.46f
private const val bioBodyWidth = 0.52f
private const val bioBodyHeight = 0.34f
private const val bioDotRadius = 0.05f
private const val bioDotCenterX = 0.5f
private const val bioDotCenterY = 0.60f
private const val bioPinLineTopY = 0.66f
private const val bioPinLineBotY = 0.76f

// Pin illustration fractions
private const val pinDotRadius = 0.075f
private const val pinDotLeftX = 0.28f
private const val pinDotMidX = 0.5f
private const val pinDotRightX = 0.72f
private const val pinDotY = 0.34f
private const val pinBarLeftX = 0.18f
private const val pinBarTopY = 0.56f
private const val pinBarWidth = 0.64f
private const val pinBarHeight = 0.16f

internal enum class AuthPromptIllustration {
    Permission,
    Biometric,
    Pin,
}

@Suppress("LongMethod")
@Composable
internal fun AuthPromptScaffold(
    title: String,
    message: String,
    illustration: AuthPromptIllustration,
    modifier: Modifier = Modifier,
    topActionText: String? = null,
    onTopAction: (() -> Unit)? = null,
    banner: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    progress: (@Composable () -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val type = RipDpiThemeTokens.type

    RipDpiIntroScaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
        topAction = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(introLayout.topActionRowHeight),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (topActionText != null && onTopAction != null) {
                    androidx.compose.material3.Text(
                        text = topActionText,
                        style = type.introAction,
                        color = colors.mutedForeground,
                        modifier =
                            Modifier
                                .ripDpiClickable(role = Role.Button, onClick = onTopAction)
                                .padding(horizontal = 12.dp),
                    )
                }
            }
        },
        footer = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = introLayout.footerBottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                progress?.let {
                    it()
                    Spacer(modifier = Modifier.height(introLayout.footerProgressGap))
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    content = footer,
                )
            }
        },
    ) {
        banner?.let {
            it()
            Spacer(modifier = Modifier.height(spacing.lg))
        }

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AuthPromptBadge(
                    illustration = illustration,
                    modifier = Modifier.size(introLayout.illustrationSize),
                )
                Spacer(modifier = Modifier.height(introLayout.illustrationToTitleGap))
                androidx.compose.material3.Text(
                    text = title,
                    style = type.introTitle,
                    color = colors.foreground,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = introLayout.titleHorizontalPadding),
                )
                Spacer(modifier = Modifier.height(introLayout.titleToBodyGap))
                androidx.compose.material3.Text(
                    text = message,
                    style = type.introBody,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = introLayout.bodyHorizontalPadding),
                )
                content?.let {
                    Spacer(modifier = Modifier.height(introLayout.bodyToContentGap))
                    it()
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun AuthPromptBadge(
    illustration: AuthPromptIllustration,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val strokeWidth = introLayout.illustrationIconStrokeWidth

    Box(
        modifier =
            modifier.border(
                introLayout.illustrationBorderWidth,
                colors.foreground,
                RoundedCornerShape(introLayout.illustrationCornerRadius),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(introLayout.illustrationIconSize)) {
            when (illustration) {
                AuthPromptIllustration.Permission -> {
                    val shield =
                        Path().apply {
                            moveTo(size.width * shieldCenterX, size.height * shieldTopY)
                            lineTo(size.width * shieldRightX, size.height * shieldShoulderY)
                            lineTo(size.width * shieldRightX, size.height * shieldWaistY)
                            cubicTo(
                                size.width * shieldRightX,
                                size.height * shieldCurveY,
                                size.width * shieldTipInnerX,
                                size.height * shieldTipY,
                                size.width * shieldCenterX,
                                size.height * shieldBottomY,
                            )
                            cubicTo(
                                size.width * shieldLeftInnerX,
                                size.height * shieldTipY,
                                size.width * shieldLeftX,
                                size.height * shieldCurveY,
                                size.width * shieldLeftX,
                                size.height * shieldWaistY,
                            )
                            lineTo(size.width * shieldLeftX, size.height * shieldShoulderY)
                            close()
                        }
                    drawPath(
                        path = shield,
                        color = colors.foreground,
                        style =
                            Stroke(
                                width = strokeWidth.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                    )
                }

                AuthPromptIllustration.Biometric -> {
                    val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                    drawArc(
                        color = colors.foreground,
                        startAngle = 210f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(size.width * bioArcLeftX, size.height * bioArcTopY),
                        size = Size(size.width * bioArcWidth, size.height * bioArcHeight),
                        style = stroke,
                    )
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * bioBodyLeftX, size.height * bioBodyTopY),
                        size = Size(size.width * bioBodyWidth, size.height * bioBodyHeight),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        style = stroke,
                    )
                    drawCircle(
                        color = colors.foreground,
                        radius = size.minDimension * bioDotRadius,
                        center = Offset(size.width * bioDotCenterX, size.height * bioDotCenterY),
                    )
                    drawLine(
                        color = colors.foreground,
                        start = Offset(size.width * bioDotCenterX, size.height * bioPinLineTopY),
                        end = Offset(size.width * bioDotCenterX, size.height * bioPinLineBotY),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                AuthPromptIllustration.Pin -> {
                    val dotRadius = size.minDimension * pinDotRadius
                    listOf(pinDotLeftX, pinDotMidX, pinDotRightX).forEach { fraction ->
                        drawCircle(
                            color = colors.foreground,
                            radius = dotRadius,
                            center = Offset(size.width * fraction, size.height * pinDotY),
                        )
                    }
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * pinBarLeftX, size.height * pinBarTopY),
                        size = Size(size.width * pinBarWidth, size.height * pinBarHeight),
                        cornerRadius = CornerRadius(cornerRadiusPill, cornerRadiusPill),
                        style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
