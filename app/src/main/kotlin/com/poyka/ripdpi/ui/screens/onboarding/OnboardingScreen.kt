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
                            .ripDpiClickable(role = Role.Button, onClick = onSkip),
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
            (introLayout.illustrationSize * 0.55f).toPx()
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
    val textAlpha = (0.24f + (pageProgress * 0.76f)).coerceIn(0f, 1f)
    val bodyAlpha = (0.18f + (pageProgress * 0.82f)).coerceIn(0f, 1f)

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
                        scaleX = 0.88f + (pageProgress * 0.12f)
                        scaleY = 0.88f + (pageProgress * 0.12f)
                        alpha = (0.4f + (pageProgress * 0.6f)).coerceIn(0f, 1f)
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
                        radius = size.minDimension * 0.33f,
                        style = Stroke(width = strokeWidth.toPx()),
                    )
                }

                OnboardingIllustration.Permission -> {
                    val shield =
                        Path().apply {
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
                    drawPath(path = shield, color = colors.foreground, style = stroke)
                }

                OnboardingIllustration.Modes -> {
                    val modeStroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * 0.12f, size.height * 0.18f),
                        size = Size(size.width * 0.76f, size.height * 0.16f),
                        cornerRadius = CornerRadius(99f, 99f),
                        style = modeStroke,
                    )
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * 0.12f, size.height * 0.66f),
                        size = Size(size.width * 0.76f, size.height * 0.16f),
                        cornerRadius = CornerRadius(99f, 99f),
                        style = modeStroke,
                    )
                    drawLine(
                        color = colors.foreground,
                        start = Offset(size.width * 0.34f, size.height * 0.34f),
                        end = Offset(size.width * 0.34f, size.height * 0.66f),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = colors.foreground,
                        start = Offset(size.width * 0.66f, size.height * 0.34f),
                        end = Offset(size.width * 0.66f, size.height * 0.66f),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                OnboardingIllustration.Diagnostics -> {
                    // Magnifying glass over heartbeat wave
                    val cx = size.width * 0.42f
                    val cy = size.height * 0.42f
                    val r = size.minDimension * 0.25f
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(cx, cy),
                        radius = r,
                        style = stroke,
                    )
                    drawLine(
                        color = colors.foreground,
                        start = Offset(cx + r * 0.7f, cy + r * 0.7f),
                        end = Offset(size.width * 0.82f, size.height * 0.82f),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                    // Heartbeat wave inside lens
                    val wave =
                        Path().apply {
                            moveTo(cx - r * 0.6f, cy)
                            lineTo(cx - r * 0.2f, cy)
                            lineTo(cx, cy - r * 0.5f)
                            lineTo(cx + r * 0.2f, cy + r * 0.3f)
                            lineTo(cx + r * 0.4f, cy)
                            lineTo(cx + r * 0.6f, cy)
                        }
                    drawPath(path = wave, color = colors.foreground, style = stroke)
                }

                OnboardingIllustration.BypassModes -> {
                    // Source dot -> two paths -> destination dot
                    val srcX = size.width * 0.1f
                    val dstX = size.width * 0.9f
                    val midY = size.height * 0.5f
                    val topY = size.height * 0.28f
                    val botY = size.height * 0.72f
                    // Source and destination dots
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(srcX, midY),
                        radius = size.minDimension * 0.06f,
                    )
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(dstX, midY),
                        radius = size.minDimension * 0.06f,
                    )
                    // Top path (VPN/shield)
                    val topPath =
                        Path().apply {
                            moveTo(srcX, midY)
                            quadraticTo(size.width * 0.3f, topY, size.width * 0.5f, topY)
                            quadraticTo(size.width * 0.7f, topY, dstX, midY)
                        }
                    drawPath(path = topPath, color = colors.foreground, style = stroke)
                    // Bottom path (Proxy)
                    val botPath =
                        Path().apply {
                            moveTo(srcX, midY)
                            quadraticTo(size.width * 0.3f, botY, size.width * 0.5f, botY)
                            quadraticTo(size.width * 0.7f, botY, dstX, midY)
                        }
                    drawPath(path = botPath, color = colors.foreground, style = stroke)
                }

                OnboardingIllustration.Privacy -> {
                    // Eye outline with strike-through
                    val eyeY = size.height * 0.5f
                    val eyePath =
                        Path().apply {
                            moveTo(size.width * 0.08f, eyeY)
                            cubicTo(
                                size.width * 0.25f,
                                size.height * 0.2f,
                                size.width * 0.75f,
                                size.height * 0.2f,
                                size.width * 0.92f,
                                eyeY,
                            )
                            cubicTo(
                                size.width * 0.75f,
                                size.height * 0.8f,
                                size.width * 0.25f,
                                size.height * 0.8f,
                                size.width * 0.08f,
                                eyeY,
                            )
                            close()
                        }
                    drawPath(path = eyePath, color = colors.foreground, style = stroke)
                    // Pupil
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(size.width * 0.5f, eyeY),
                        radius = size.minDimension * 0.1f,
                        style = stroke,
                    )
                    // Strike-through line
                    drawLine(
                        color = colors.foreground,
                        start = Offset(size.width * 0.15f, size.height * 0.15f),
                        end = Offset(size.width * 0.85f, size.height * 0.85f),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private fun PagerState.onboardingPageOffset(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

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
