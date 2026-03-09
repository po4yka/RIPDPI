package com.poyka.ripdpi.ui.screens.permissions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionState
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiPageIndicators
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val PermissionProgressPage = 1
private const val PermissionProgressPageCount = 3

@Composable
fun VpnPermissionRoute(
    onDismiss: () -> Unit,
    onGranted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.connectionState) {
        if (
            uiState.connectionState == ConnectionState.Connecting ||
            uiState.connectionState == ConnectionState.Connected
        ) {
            onGranted()
        }
    }

    VpnPermissionScreen(
        uiState = uiState,
        onDismiss = onDismiss,
        onContinue = { viewModel.requestVpnPermission(context) },
        modifier = modifier,
    )
}

@Composable
fun VpnPermissionScreen(
    uiState: MainUiState,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val introLayout = RipDpiThemeTokens.introLayout

    AuthPromptScaffold(
        title = stringResource(R.string.permissions_vpn_title),
        message = stringResource(R.string.permissions_vpn_body),
        illustration = AuthPromptIllustration.Permission,
        modifier = modifier,
        topActionText = stringResource(R.string.permissions_vpn_not_now),
        onTopAction = onDismiss,
        banner = {
            if (uiState.connectionState == ConnectionState.Error && uiState.errorMessage != null) {
                WarningBanner(
                    title = stringResource(R.string.permissions_vpn_error_title),
                    message = uiState.errorMessage,
                    tone = WarningBannerTone.Error,
                )
            }
        },
        progress = {
            RipDpiPageIndicators(
                currentPage = PermissionProgressPage,
                pageCount = PermissionProgressPageCount,
            )
        },
        footer = {
            RipDpiButton(
                text = if (uiState.connectionState == ConnectionState.Connecting) {
                    stringResource(R.string.home_connection_button_connecting)
                } else {
                    stringResource(R.string.permissions_vpn_continue)
                },
                onClick = onContinue,
                enabled = uiState.connectionState != ConnectionState.Connecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = introLayout.footerButtonHorizontalInset)
                    .heightIn(min = introLayout.footerButtonMinHeight),
                trailingIcon = RipDpiIcons.ChevronRight,
            )
        },
    )
}

internal enum class AuthPromptIllustration {
    Permission,
    Biometric,
    Pin,
}

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
    val layout = RipDpiThemeTokens.layout
    val introLayout = RipDpiThemeTokens.introLayout
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = layout.horizontalPadding),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(introLayout.topActionRowHeight),
            contentAlignment = Alignment.TopEnd,
        ) {
            if (topActionText != null && onTopAction != null) {
                androidx.compose.material3.Text(
                    text = topActionText,
                    style = type.introAction,
                    color = colors.mutedForeground,
                    modifier = Modifier
                        .padding(top = introLayout.topActionTopPadding)
                        .clickable(role = Role.Button, onClick = onTopAction),
                )
            }
        }

        banner?.let {
            it()
            Spacer(modifier = Modifier.height(spacing.lg))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = introLayout.titleHorizontalPadding),
                )
                Spacer(modifier = Modifier.height(introLayout.titleToBodyGap))
                androidx.compose.material3.Text(
                    text = message,
                    style = type.introBody,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = introLayout.bodyHorizontalPadding),
                )
                content?.let {
                    Spacer(modifier = Modifier.height(introLayout.bodyToContentGap))
                    it()
                }
            }
        }

        Column(
            modifier = Modifier
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
    }
}

@Composable
private fun AuthPromptBadge(
    illustration: AuthPromptIllustration,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val introLayout = RipDpiThemeTokens.introLayout
    val strokeWidth = introLayout.illustrationIconStrokeWidth

    Box(
        modifier = modifier.border(
            introLayout.illustrationBorderWidth,
            colors.foreground,
            RoundedCornerShape(introLayout.illustrationCornerRadius),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(introLayout.illustrationIconSize)) {
            when (illustration) {
                AuthPromptIllustration.Permission -> {
                    val shield = Path().apply {
                        moveTo(size.width * 0.5f, size.height * 0.12f)
                        lineTo(size.width * 0.78f, size.height * 0.22f)
                        lineTo(size.width * 0.78f, size.height * 0.48f)
                        cubicTo(
                            size.width * 0.78f,
                            size.height * 0.72f,
                            size.width * 0.62f,
                            size.height * 0.86f,
                            size.width * 0.5f,
                            size.height * 0.92f,
                        )
                        cubicTo(
                            size.width * 0.38f,
                            size.height * 0.86f,
                            size.width * 0.22f,
                            size.height * 0.72f,
                            size.width * 0.22f,
                            size.height * 0.48f,
                        )
                        lineTo(size.width * 0.22f, size.height * 0.22f)
                        close()
                    }
                    drawPath(
                        path = shield,
                        color = colors.foreground,
                        style = Stroke(
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
                        topLeft = Offset(size.width * 0.22f, size.height * 0.08f),
                        size = Size(size.width * 0.56f, size.height * 0.54f),
                        style = stroke,
                    )
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * 0.24f, size.height * 0.46f),
                        size = Size(size.width * 0.52f, size.height * 0.34f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        style = stroke,
                    )
                    drawCircle(
                        color = colors.foreground,
                        radius = size.minDimension * 0.05f,
                        center = Offset(size.width * 0.5f, size.height * 0.60f),
                    )
                    drawLine(
                        color = colors.foreground,
                        start = Offset(size.width * 0.5f, size.height * 0.66f),
                        end = Offset(size.width * 0.5f, size.height * 0.76f),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                AuthPromptIllustration.Pin -> {
                    val dotRadius = size.minDimension * 0.075f
                    listOf(0.28f, 0.5f, 0.72f).forEach { fraction ->
                        drawCircle(
                            color = colors.foreground,
                            radius = dotRadius,
                            center = Offset(size.width * fraction, size.height * 0.34f),
                        )
                    }
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * 0.18f, size.height * 0.56f),
                        size = Size(size.width * 0.64f, size.height * 0.16f),
                        cornerRadius = CornerRadius(99f, 99f),
                        style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun VpnPermissionScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        VpnPermissionScreen(
            uiState = MainUiState(),
            onDismiss = {},
            onContinue = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun VpnPermissionScreenErrorPreview() {
    RipDpiTheme(themePreference = "dark") {
        VpnPermissionScreen(
            uiState = MainUiState(
                connectionState = ConnectionState.Error,
                errorMessage = "VPN permission denied",
            ),
            onDismiss = {},
            onContinue = {},
        )
    }
}
