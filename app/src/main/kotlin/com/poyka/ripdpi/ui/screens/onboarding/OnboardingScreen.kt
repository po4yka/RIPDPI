package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConnectionTestState
import com.poyka.ripdpi.activities.OnboardingEffect
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.OnboardingViewModel
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.indicators.RipDpiPageIndicators
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.scaffold.RipDpiIntroScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.absoluteValue

// Animation / alpha keyframe fractions
private const val alphaTextMin = 0.24f
private const val alphaTextRange = 0.76f
private const val alphaBodyMin = 0.18f
private const val alphaBodyRange = 0.82f
private const val alphaIllusMin = 0.4f
private const val alphaIllusRange = 0.6f

// Illustration scale keyframe fractions
private const val scaleIllusBase = 0.88f
private const val scaleIllusRange = 0.12f

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

// Modes illustration fractions
private const val modesBarLeftX = 0.12f
private const val modesBarTopY = 0.18f
private const val modesBarWidth = 0.76f
private const val modesBarHeight = 0.16f
private const val modesBarBottomY = 0.66f
private const val modesLineXLeft = 0.34f
private const val modesLineXRight = 0.66f
private const val modesLineTopY = 0.34f

// Diagnostics illustration fractions
private const val diagLensCx = 0.42f
private const val diagLensCy = 0.42f
private const val diagHandleEnd = 0.82f

// BypassModes illustration fractions
private const val bypassSrcX = 0.1f
private const val bypassDstX = 0.9f
private const val bypassMidY = 0.5f
private const val bypassTopY = 0.28f
private const val bypassBotY = 0.72f
private const val bypassCtrlXNear = 0.3f
private const val bypassCtrlXFar = 0.7f
private const val bypassDotRadius = 0.06f

// Privacy (eye) illustration fractions
private const val eyeLeftX = 0.08f
private const val eyeRightX = 0.92f
private const val eyeMidX = 0.5f
private const val eyeCtrlInnerX = 0.25f
private const val eyeCtrlOuterX = 0.75f
private const val eyeUpperY = 0.2f
private const val eyeLowerY = 0.8f
private const val eyePupilRadius = 0.1f
private const val eyeStrikeNear = 0.15f
private const val eyeStrikeFar = 0.85f

// LocalFirst illustration fraction
private const val localFirstRadius = 0.33f

// Diagnostics heartbeat wave fractions (relative to lens radius r)
private const val diagLensRadius = 0.25f
private const val diagHandleOffset = 0.7f
private const val diagWaveFar = 0.6f
private const val diagWaveNear = 0.2f
private const val diagWavePeak = 0.5f
private const val diagWaveTrough = 0.3f
private const val diagWaveMidOut = 0.4f

// Illustration travel fraction for entrance animation
private const val illusTravelFraction = 0.55f

@Composable
fun OnboardingRoute(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val currentOnComplete by rememberUpdatedState(onComplete)

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            if (effect is OnboardingEffect.OnboardingComplete) {
                currentOnComplete()
            }
        }
    }

    OnboardingScreen(
        uiState = uiState,
        modifier = modifier,
        onPageChanged = viewModel::setCurrentPage,
        onSkip = viewModel::skip,
        onModeSelected = viewModel::selectMode,
        onDnsSelected = viewModel::selectDnsProvider,
        onRunTest = viewModel::runConnectionTest,
        onContinue = {
            if (uiState.currentPage == OnboardingPages.lastIndex) {
                viewModel.finish()
            } else {
                viewModel.nextPage()
            }
        },
    )
}

@Suppress("LongMethod")
@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onPageChanged: (Int) -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
    onModeSelected: (Mode) -> Unit,
    onDnsSelected: (String) -> Unit,
    onRunTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val pagerState =
        rememberPagerState(
            initialPage = uiState.currentPage.coerceIn(0, OnboardingPages.lastIndex),
            pageCount = { OnboardingPages.size },
        )

    LaunchedEffect(uiState.currentPage) {
        val targetPage = uiState.currentPage.coerceIn(0, OnboardingPages.lastIndex)
        if (targetPage != pagerState.settledPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != uiState.currentPage) {
            onPageChanged(pagerState.settledPage)
        }
    }

    val settledPage = pagerState.settledPage.coerceIn(0, OnboardingPages.lastIndex)
    val currentPage = OnboardingPages[settledPage]
    val isLastPage = settledPage == OnboardingPages.lastIndex

    RipDpiIntroScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Onboarding))
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
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    style = type.introAction,
                    color = colors.mutedForeground,
                    modifier =
                        Modifier
                            .ripDpiTestTag(RipDpiTestTags.OnboardingSkip)
                            .ripDpiClickable(role = Role.Button, onClick = onSkip)
                            .padding(horizontal = 12.dp),
                )
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
                RipDpiPageIndicators(
                    currentPage = settledPage,
                    pageCount = uiState.totalPages.coerceAtMost(OnboardingPages.size),
                    sectionBreakAfter = OnboardingInfoPageCount,
                )
                Spacer(modifier = Modifier.height(introLayout.footerProgressGap))
                RipDpiButton(
                    text = stringResource(currentPage.buttonLabelRes),
                    onClick = onContinue,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = introLayout.footerButtonHorizontalInset)
                            .heightIn(min = introLayout.footerButtonMinHeight)
                            .ripDpiTestTag(RipDpiTestTags.OnboardingContinue),
                    trailingIcon = if (isLastPage) null else RipDpiIcons.ChevronRight,
                )
            }
        },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) { page ->
            when (val pageModel = OnboardingPages[page]) {
                is OnboardingPage.Informational -> {
                    OnboardingInfoPageScene(
                        pageModel = pageModel,
                        pageOffset = pagerState.onboardingPageOffset(page),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is OnboardingPage.Setup -> {
                    OnboardingSetupPageScene(
                        pageModel = pageModel,
                        uiState = uiState,
                        onModeSelected = onModeSelected,
                        onDnsSelected = onDnsSelected,
                        onRunTest = onRunTest,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingInfoPageScene(
    pageModel: OnboardingPage.Informational,
    pageOffset: Float,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val density = LocalDensity.current
    val clampedOffset = pageOffset.coerceIn(-1f, 1f)
    val pageProgress = (1f - clampedOffset.absoluteValue).coerceIn(0f, 1f)
    val illustrationTravelPx =
        with(density) {
            (introLayout.illustrationSize * illusTravelFraction).toPx()
        }
    val titleTravelPx =
        with(density) {
            28.dp.toPx()
        }
    val bodyTravelPx =
        with(density) {
            42.dp.toPx()
        }
    val illustrationLiftPx =
        with(density) {
            12.dp.toPx()
        }
    val textAlpha = (alphaTextMin + (pageProgress * alphaTextRange)).coerceIn(0f, 1f)
    val bodyAlpha = (alphaBodyMin + (pageProgress * alphaBodyRange)).coerceIn(0f, 1f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OnboardingIllustrationBox(
            illustration = pageModel.illustration,
            modifier =
                Modifier
                    .size(introLayout.illustrationSize)
                    .graphicsLayer {
                        translationX = -clampedOffset * illustrationTravelPx
                        translationY = (1f - pageProgress) * illustrationLiftPx
                        rotationZ = clampedOffset * 2f
                        scaleX = scaleIllusBase + (pageProgress * scaleIllusRange)
                        scaleY = scaleIllusBase + (pageProgress * scaleIllusRange)
                        alpha = (alphaIllusMin + (pageProgress * alphaIllusRange)).coerceIn(0f, 1f)
                    },
        )
        Spacer(modifier = Modifier.height(introLayout.illustrationToTitleGap))
        Text(
            text = stringResource(pageModel.titleRes),
            style = type.introTitle,
            color = colors.foreground,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = introLayout.titleHorizontalPadding)
                    .graphicsLayer {
                        translationX = clampedOffset * titleTravelPx
                        alpha = textAlpha
                    },
        )
        Spacer(modifier = Modifier.height(introLayout.titleToBodyGap))
        Text(
            text = stringResource(pageModel.descriptionRes),
            style = type.introBody,
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = introLayout.bodyHorizontalPadding)
                    .graphicsLayer {
                        translationX = clampedOffset * bodyTravelPx
                        alpha = bodyAlpha
                    },
        )
    }
}

@Composable
private fun OnboardingSetupPageScene(
    pageModel: OnboardingPage.Setup,
    uiState: OnboardingUiState,
    onModeSelected: (Mode) -> Unit,
    onDnsSelected: (String) -> Unit,
    onRunTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val introLayout = rememberRipDpiIntroScaffoldMetrics()

    Column(
        modifier = modifier.padding(horizontal = introLayout.bodyHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(introLayout.illustrationToTitleGap))
        Text(
            text = stringResource(pageModel.titleRes),
            style = type.introTitle,
            color = colors.foreground,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = introLayout.titleHorizontalPadding),
        )
        Spacer(modifier = Modifier.height(introLayout.titleToBodyGap))

        when (pageModel.kind) {
            SetupPageKind.ModeSelection -> {
                OnboardingModeSelectionContent(
                    selectedMode = uiState.selectedMode,
                    onModeSelected = onModeSelected,
                )
            }

            SetupPageKind.DnsSelection -> {
                OnboardingDnsSelectionContent(
                    selectedProviderId = uiState.selectedDnsProviderId,
                    onDnsSelected = onDnsSelected,
                )
            }

            SetupPageKind.ConnectionTest -> {
                OnboardingConnectionTestContent(
                    testState = uiState.connectionTestState,
                    onRunTest = onRunTest,
                )
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun OnboardingIllustrationBox(
    illustration: OnboardingIllustration,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val strokeWidth = introLayout.illustrationIconStrokeWidth

    Box(
        modifier =
            modifier
                .border(
                    introLayout.illustrationBorderWidth,
                    colors.foreground,
                    RoundedCornerShape(introLayout.illustrationCornerRadius),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(introLayout.illustrationIconSize)) {
            val stroke =
                Stroke(
                    width = strokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )

            when (illustration) {
                OnboardingIllustration.LocalFirst -> {
                    drawCircle(
                        color = colors.foreground,
                        radius = size.minDimension * localFirstRadius,
                        style = Stroke(width = strokeWidth.toPx()),
                    )
                }

                OnboardingIllustration.Permission -> {
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
                    drawPath(path = shield, color = colors.foreground, style = stroke)
                }

                OnboardingIllustration.Modes -> {
                    val modeStroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * modesBarLeftX, size.height * modesBarTopY),
                        size = Size(size.width * modesBarWidth, size.height * modesBarHeight),
                        cornerRadius = CornerRadius(cornerRadiusPill, cornerRadiusPill),
                        style = modeStroke,
                    )
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * modesBarLeftX, size.height * modesBarBottomY),
                        size = Size(size.width * modesBarWidth, size.height * modesBarHeight),
                        cornerRadius = CornerRadius(cornerRadiusPill, cornerRadiusPill),
                        style = modeStroke,
                    )
                    drawLine(
                        color = colors.foreground,
                        start = Offset(size.width * modesLineXLeft, size.height * modesLineTopY),
                        end = Offset(size.width * modesLineXLeft, size.height * modesBarBottomY),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = colors.foreground,
                        start = Offset(size.width * modesLineXRight, size.height * modesLineTopY),
                        end = Offset(size.width * modesLineXRight, size.height * modesBarBottomY),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                OnboardingIllustration.Diagnostics -> {
                    // Magnifying glass over heartbeat wave
                    val cx = size.width * diagLensCx
                    val cy = size.height * diagLensCy
                    val r = size.minDimension * diagLensRadius
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(cx, cy),
                        radius = r,
                        style = stroke,
                    )
                    drawLine(
                        color = colors.foreground,
                        start = Offset(cx + r * diagHandleOffset, cy + r * diagHandleOffset),
                        end = Offset(size.width * diagHandleEnd, size.height * diagHandleEnd),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                    // Heartbeat wave inside lens
                    val wave =
                        Path().apply {
                            moveTo(cx - r * diagWaveFar, cy)
                            lineTo(cx - r * diagWaveNear, cy)
                            lineTo(cx, cy - r * diagWavePeak)
                            lineTo(cx + r * diagWaveNear, cy + r * diagWaveTrough)
                            lineTo(cx + r * diagWaveMidOut, cy)
                            lineTo(cx + r * diagWaveFar, cy)
                        }
                    drawPath(path = wave, color = colors.foreground, style = stroke)
                }

                OnboardingIllustration.BypassModes -> {
                    // Source dot -> two paths -> destination dot
                    val srcX = size.width * bypassSrcX
                    val dstX = size.width * bypassDstX
                    val midY = size.height * bypassMidY
                    val topY = size.height * bypassTopY
                    val botY = size.height * bypassBotY
                    // Source and destination dots
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(srcX, midY),
                        radius = size.minDimension * bypassDotRadius,
                    )
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(dstX, midY),
                        radius = size.minDimension * bypassDotRadius,
                    )
                    // Top path (VPN/shield)
                    val topPath =
                        Path().apply {
                            moveTo(srcX, midY)
                            quadraticTo(size.width * bypassCtrlXNear, topY, size.width * bypassMidY, topY)
                            quadraticTo(size.width * bypassCtrlXFar, topY, dstX, midY)
                        }
                    drawPath(path = topPath, color = colors.foreground, style = stroke)
                    // Bottom path (Proxy)
                    val botPath =
                        Path().apply {
                            moveTo(srcX, midY)
                            quadraticTo(size.width * bypassCtrlXNear, botY, size.width * bypassMidY, botY)
                            quadraticTo(size.width * bypassCtrlXFar, botY, dstX, midY)
                        }
                    drawPath(path = botPath, color = colors.foreground, style = stroke)
                }

                OnboardingIllustration.Privacy -> {
                    // Eye outline with strike-through
                    val eyeY = size.height * eyeMidX
                    val eyePath =
                        Path().apply {
                            moveTo(size.width * eyeLeftX, eyeY)
                            cubicTo(
                                size.width * eyeCtrlInnerX,
                                size.height * eyeUpperY,
                                size.width * eyeCtrlOuterX,
                                size.height * eyeUpperY,
                                size.width * eyeRightX,
                                eyeY,
                            )
                            cubicTo(
                                size.width * eyeCtrlOuterX,
                                size.height * eyeLowerY,
                                size.width * eyeCtrlInnerX,
                                size.height * eyeLowerY,
                                size.width * eyeLeftX,
                                eyeY,
                            )
                            close()
                        }
                    drawPath(path = eyePath, color = colors.foreground, style = stroke)
                    // Pupil
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(size.width * eyeMidX, eyeY),
                        radius = size.minDimension * eyePupilRadius,
                        style = stroke,
                    )
                    // Strike-through line
                    drawLine(
                        color = colors.foreground,
                        start = Offset(size.width * eyeStrikeNear, size.height * eyeStrikeNear),
                        end = Offset(size.width * eyeStrikeFar, size.height * eyeStrikeFar),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private fun PagerState.onboardingPageOffset(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        OnboardingScreen(
            uiState = OnboardingUiState(currentPage = 0, totalPages = OnboardingPages.size),
            onPageChanged = {},
            onSkip = {},
            onContinue = {},
            onModeSelected = {},
            onDnsSelected = {},
            onRunTest = {},
        )
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingScreenSetupPreview() {
    RipDpiTheme(themePreference = "light") {
        OnboardingScreen(
            uiState =
                OnboardingUiState(
                    currentPage = OnboardingInfoPageCount,
                    totalPages = OnboardingPages.size,
                ),
            onPageChanged = {},
            onSkip = {},
            onContinue = {},
            onModeSelected = {},
            onDnsSelected = {},
            onRunTest = {},
        )
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        OnboardingScreen(
            uiState = OnboardingUiState(currentPage = 2, totalPages = OnboardingPages.size),
            onPageChanged = {},
            onSkip = {},
            onContinue = {},
            onModeSelected = {},
            onDnsSelected = {},
            onRunTest = {},
        )
    }
}
