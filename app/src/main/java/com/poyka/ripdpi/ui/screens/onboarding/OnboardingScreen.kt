package com.poyka.ripdpi.ui.screens.onboarding

import androidx.annotation.StringRes
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.OnboardingEffect
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.OnboardingViewModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.indicators.RipDpiPageIndicators
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.scaffold.RipDpiIntroScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private val OnboardingPages =
    listOf(
        OnboardingPageModel(
            titleRes = R.string.onboarding_local_first_title,
            descriptionRes = R.string.onboarding_local_first_body,
            buttonLabelRes = R.string.onboarding_continue,
            illustration = OnboardingIllustration.LocalFirst,
        ),
        OnboardingPageModel(
            titleRes = R.string.onboarding_permission_title,
            descriptionRes = R.string.onboarding_permission_body,
            buttonLabelRes = R.string.onboarding_continue,
            illustration = OnboardingIllustration.Permission,
        ),
        OnboardingPageModel(
            titleRes = R.string.onboarding_modes_title,
            descriptionRes = R.string.onboarding_modes_body,
            buttonLabelRes = R.string.onboarding_get_started,
            illustration = OnboardingIllustration.Modes,
        ),
    )

@Composable
fun OnboardingRoute(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is OnboardingEffect.OnboardingComplete) {
                onComplete()
            }
        }
    }

    OnboardingScreen(
        uiState = uiState,
        modifier = modifier,
        onPageChanged = viewModel::setCurrentPage,
        onSkip = viewModel::skip,
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
        if (targetPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != uiState.currentPage) {
            onPageChanged(pagerState.currentPage)
        }
    }

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
                contentAlignment = Alignment.TopEnd,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    style = type.introAction,
                    color = colors.mutedForeground,
                    modifier =
                        Modifier
                            .padding(top = introLayout.topActionTopPadding)
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
                    currentPage = pagerState.currentPage,
                    pageCount = uiState.totalPages.coerceAtMost(OnboardingPages.size),
                )
                Spacer(modifier = Modifier.height(introLayout.footerProgressGap))
                RipDpiButton(
                    text = stringResource(OnboardingPages[pagerState.currentPage].buttonLabelRes),
                    onClick = onContinue,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = introLayout.footerButtonHorizontalInset)
                            .heightIn(min = introLayout.footerButtonMinHeight),
                    trailingIcon = RipDpiIcons.ChevronRight,
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
            val pageModel = OnboardingPages[page]
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                OnboardingIllustration(
                    illustration = pageModel.illustration,
                    modifier = Modifier.size(introLayout.illustrationSize),
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
                            .padding(horizontal = introLayout.titleHorizontalPadding),
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
                            .padding(horizontal = introLayout.bodyHorizontalPadding),
                )
            }
        }
    }
}

@Composable
private fun OnboardingIllustration(
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

                OnboardingIllustration.Modes -> {
                    val stroke =
                        Stroke(
                            width = strokeWidth.toPx(),
                            cap = StrokeCap.Round,
                        )
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * 0.12f, size.height * 0.18f),
                        size = Size(size.width * 0.76f, size.height * 0.16f),
                        cornerRadius = CornerRadius(99f, 99f),
                        style = stroke,
                    )
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * 0.12f, size.height * 0.66f),
                        size = Size(size.width * 0.76f, size.height * 0.16f),
                        cornerRadius = CornerRadius(99f, 99f),
                        style = stroke,
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
            }
        }
    }
}

private data class OnboardingPageModel(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    @param:StringRes val buttonLabelRes: Int,
    val illustration: OnboardingIllustration,
)

private enum class OnboardingIllustration {
    LocalFirst,
    Permission,
    Modes,
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        OnboardingScreen(
            uiState = OnboardingUiState(currentPage = 0, totalPages = OnboardingPages.size),
            onPageChanged = {},
            onSkip = {},
            onContinue = {},
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
        )
    }
}
