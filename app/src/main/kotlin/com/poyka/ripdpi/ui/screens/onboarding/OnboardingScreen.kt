package com.poyka.ripdpi.ui.screens.onboarding

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.OnboardingEffect
import com.poyka.ripdpi.activities.OnboardingUiState
import com.poyka.ripdpi.activities.OnboardingValidationState
import com.poyka.ripdpi.activities.OnboardingViewModel
import com.poyka.ripdpi.activities.isBusy
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.indicators.RipDpiPageIndicators
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.flow.SharedFlow
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

// LocalFirst illustration fractions — device outline containing a local node + short tunnel stub
private const val localDeviceLeftX = 0.30f
private const val localDeviceRightX = 0.70f
private const val localDeviceTopY = 0.16f
private const val localDeviceBottomY = 0.84f
private const val localDeviceCorner = 12f
private const val localNodeRadius = 0.085f
private const val localTunnelStartX = 0.50f
private const val localTunnelEndX = 0.86f
private const val localTunnelY = 0.5f
private const val localTunnelTickInset = 0.04f
private const val localTunnelTickHeight = 0.07f

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
    onOpenAdvancedDns: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationsPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onNotificationPermissionResult(
                result =
                    if (granted) {
                        PermissionResult.Granted
                    } else {
                        PermissionResult.Denied
                    },
            )
        }
    val vpnConsentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onVpnPermissionResult(
                result =
                    if (VpnService.prepare(context) == null) {
                        PermissionResult.Granted
                    } else {
                        PermissionResult.Denied
                    },
            )
        }

    OnboardingEffectsHandler(
        effects = viewModel.effects,
        onComplete = onComplete,
        onRequestNotificationsPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.onNotificationPermissionResult(PermissionResult.Granted)
            }
        },
        onRequestVpnConsent = vpnConsentLauncher::launch,
    )

    OnboardingScreen(
        uiState = uiState,
        modifier = modifier,
        onPageChanged = remember(viewModel) { viewModel::setCurrentPage },
        onSkip = remember(viewModel) { viewModel::skip },
        onModeSelected = remember(viewModel) { viewModel::selectMode },
        onDnsSelected = remember(viewModel) { viewModel::selectDnsProvider },
        onOpenAdvancedDns = onOpenAdvancedDns,
        onRunValidation = remember(viewModel) { viewModel::runValidation },
        onFinishKeepingRunning = remember(viewModel) { viewModel::finishKeepingRunning },
        onFinishDisconnected = remember(viewModel) { viewModel::finishDisconnected },
        onFinishAnyway = remember(viewModel) { viewModel::finishAnyway },
        onAcceptSuggestedMode = remember(viewModel) { viewModel::acceptSuggestedMode },
        onChangeDns = remember(viewModel) { { viewModel.setCurrentPage(OnboardingDnsPageIndex) } },
        onContinue = remember(viewModel) { viewModel::nextPage },
    )
}

@Composable
internal fun OnboardingEffectsHandler(
    effects: SharedFlow<OnboardingEffect>,
    onComplete: () -> Unit,
    onRequestNotificationsPermission: () -> Unit,
    onRequestVpnConsent: (Intent) -> Unit,
) {
    val currentOnComplete by rememberUpdatedState(onComplete)
    val currentOnRequestNotificationsPermission by rememberUpdatedState(onRequestNotificationsPermission)
    val currentOnRequestVpnConsent by rememberUpdatedState(onRequestVpnConsent)

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                OnboardingEffect.OnboardingComplete -> currentOnComplete()
                OnboardingEffect.RequestNotificationsPermission -> currentOnRequestNotificationsPermission()
                is OnboardingEffect.RequestVpnConsent -> currentOnRequestVpnConsent(effect.intent)
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onPageChanged: (Int) -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
    onModeSelected: (Mode) -> Unit,
    onDnsSelected: (String) -> Unit,
    onOpenAdvancedDns: () -> Unit,
    onRunValidation: () -> Unit,
    onFinishKeepingRunning: () -> Unit,
    onFinishDisconnected: () -> Unit,
    onFinishAnyway: () -> Unit,
    onAcceptSuggestedMode: () -> Unit,
    onChangeDns: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val layout = RipDpiThemeTokens.layout
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
    val validationState = uiState.validationState
    val validationBusy = validationState.isBusy
    val pageCount = uiState.totalPages.coerceAtMost(OnboardingPages.size)

    val skipVisible = !(isLastPage && validationBusy)
    val skipLabelRes =
        when (settledPage) {
            0 -> R.string.onboarding_skip_setup
            OnboardingPages.lastIndex -> R.string.onboarding_skip_test
            else -> R.string.onboarding_use_recommended
        }
    val onSkipClick: () -> Unit = if (isLastPage) onFinishAnyway else onSkip

    Box(
        modifier =
            modifier
                .ripDpiAutomationTreeRoot()
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Onboarding))
                .fillMaxSize()
                .background(colors.background),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .widthIn(max = layout.formMaxWidth)
                    .displayCutoutPadding()
                    .padding(horizontal = layout.horizontalPadding),
        ) {
            // 1) TOP BAR — status inset, fixed height, skip end-aligned
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(introLayout.topActionRowHeight),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (skipVisible) {
                    TextButton(
                        onClick = onSkipClick,
                        modifier =
                            Modifier
                                .ripDpiTestTag(RipDpiTestTags.OnboardingSkip)
                                .height(introLayout.topActionRowHeight),
                    ) {
                        Text(
                            text = stringResource(skipLabelRes),
                            style = type.introAction,
                            color = colors.mutedForeground,
                        )
                    }
                }
            }

            // 2) CONTENT — weighted; each page renders its own title at the top
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !validationBusy,
                    modifier = Modifier.fillMaxSize(),
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
                                onOpenAdvancedDns = onOpenAdvancedDns,
                                onAcceptSuggestedMode = onAcceptSuggestedMode,
                                onChangeDns = onChangeDns,
                                onFinishDisconnected = onFinishDisconnected,
                                onFinishAnyway = onFinishAnyway,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // 3) BOTTOM BAR — nav inset, page indicator + full-width primary CTA
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = introLayout.footerBottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RipDpiPageIndicators(
                    currentPage = settledPage,
                    pageCount = pageCount,
                    sectionBreakAfter = OnboardingInfoPageCount,
                    accessibilityLabel =
                        stringResource(
                            R.string.onboarding_step_progress,
                            settledPage + 1,
                            pageCount,
                        ),
                )
                Spacer(modifier = Modifier.height(introLayout.footerProgressGap))
                OnboardingFooterCta(
                    isLastPage = isLastPage,
                    continueLabelRes = currentPage.buttonLabelRes,
                    validationState = validationState,
                    onContinue = onContinue,
                    onRunValidation = onRunValidation,
                    onFinishKeepingRunning = onFinishKeepingRunning,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = introLayout.footerButtonMinHeight),
                )
            }
        }
    }
}

/**
 * State-driven footer primary CTA. Identical full-width pattern across all 4 pages.
 * On pages 0–2 it is the page's Continue/Next action; on the ConnectionTest page it is driven by
 * [validationState] (Start test / Testing… disabled / Finish / Retry).
 */
@Composable
private fun OnboardingFooterCta(
    isLastPage: Boolean,
    continueLabelRes: Int,
    validationState: OnboardingValidationState,
    onContinue: () -> Unit,
    onRunValidation: () -> Unit,
    onFinishKeepingRunning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isLastPage) {
        RipDpiButton(
            text = stringResource(continueLabelRes),
            onClick = onContinue,
            trailingIcon = RipDpiIcons.ChevronRight,
            modifier = modifier.ripDpiTestTag(RipDpiTestTags.OnboardingContinue),
        )
        return
    }

    when (validationState) {
        is OnboardingValidationState.Success -> {
            RipDpiButton(
                text = stringResource(R.string.onboarding_setup_finish),
                onClick = onFinishKeepingRunning,
                modifier = modifier.ripDpiTestTag(RipDpiTestTags.OnboardingFinishKeepRunning),
            )
        }

        is OnboardingValidationState.Failed -> {
            RipDpiButton(
                text = stringResource(R.string.onboarding_test_retry),
                onClick = onRunValidation,
                modifier = modifier.ripDpiTestTag(RipDpiTestTags.OnboardingValidateAction),
            )
        }

        OnboardingValidationState.RequestingNotifications,
        OnboardingValidationState.RequestingVpnConsent,
        is OnboardingValidationState.StartingMode,
        is OnboardingValidationState.CheckingDns,
        is OnboardingValidationState.RunningTrafficCheck,
        -> {
            RipDpiButton(
                text = stringResource(R.string.onboarding_test_running_cta),
                onClick = {},
                enabled = false,
                loading = true,
                modifier = modifier,
            )
        }

        OnboardingValidationState.Idle -> {
            RipDpiButton(
                text = stringResource(R.string.onboarding_test_start),
                onClick = onRunValidation,
                modifier = modifier.ripDpiTestTag(RipDpiTestTags.OnboardingValidateAction),
            )
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
    val spacing = RipDpiThemeTokens.spacing
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
            (introLayout.illustrationSize * 0.35f).toPx()
        }
    val bodyTravelPx =
        with(density) {
            (introLayout.illustrationSize * 0.52f).toPx()
        }
    val illustrationLiftPx =
        with(density) {
            (introLayout.illustrationSize * 0.15f).toPx()
        }
    val textAlpha = (alphaTextMin + (pageProgress * alphaTextRange)).coerceIn(0f, 1f)
    val bodyAlpha = (alphaBodyMin + (pageProgress * alphaBodyRange)).coerceIn(0f, 1f)

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = introLayout.bodyHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(spacing.section))
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
        Spacer(modifier = Modifier.height(spacing.xl))
        OnboardingChipsRow(
            labels =
                listOf(
                    R.string.onboarding_chip_no_account,
                    R.string.onboarding_chip_no_telemetry,
                    R.string.onboarding_chip_no_cloud_sync,
                ),
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        OnboardingChipsRow(
            labels =
                listOf(
                    R.string.onboarding_chip_local_vpn,
                    R.string.onboarding_chip_local_proxy,
                    R.string.onboarding_chip_local_config,
                ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingChipsRow(
    labels: List<Int>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        labels.forEach { labelRes ->
            OnboardingChip(text = stringResource(labelRes))
        }
    }
}

/** Monochrome bordered pill used for the intro privacy + tech chips. Wraps via [FlowRow]. */
@Composable
private fun OnboardingChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.shapes
    val introLayout = rememberRipDpiIntroScaffoldMetrics()

    Box(
        modifier =
            modifier
                .border(
                    width = introLayout.illustrationBorderWidth,
                    color = colors.border,
                    shape = shapes.full,
                ).padding(horizontal = spacing.md, vertical = spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.smallLabel,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun OnboardingSetupPageScene(
    pageModel: OnboardingPage.Setup,
    uiState: OnboardingUiState,
    onModeSelected: (Mode) -> Unit,
    onDnsSelected: (String) -> Unit,
    onOpenAdvancedDns: () -> Unit,
    onAcceptSuggestedMode: () -> Unit,
    onChangeDns: () -> Unit,
    onFinishDisconnected: () -> Unit,
    onFinishAnyway: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val introLayout = rememberRipDpiIntroScaffoldMetrics()

    Column(
        modifier = modifier.padding(horizontal = introLayout.bodyHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(spacing.section))
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
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            SetupPageKind.DnsSelection -> {
                OnboardingDnsSelectionContent(
                    selectedProviderId = uiState.selectedDnsProviderId,
                    onDnsSelected = onDnsSelected,
                    onOpenAdvancedDns = onOpenAdvancedDns,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            SetupPageKind.ConnectionTest -> {
                OnboardingModeValidationContent(
                    uiState = uiState,
                    onAcceptSuggestedMode = onAcceptSuggestedMode,
                    onChangeDns = onChangeDns,
                    onFinishDisconnected = onFinishDisconnected,
                    onFinishAnyway = onFinishAnyway,
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
                    // Device outline (rounded rect) containing a local node dot with a short
                    // tunnel stub that terminates in a closed end — "traffic stays local".
                    drawRoundRect(
                        color = colors.foreground,
                        topLeft = Offset(size.width * localDeviceLeftX, size.height * localDeviceTopY),
                        size =
                            Size(
                                size.width * (localDeviceRightX - localDeviceLeftX),
                                size.height * (localDeviceBottomY - localDeviceTopY),
                            ),
                        cornerRadius = CornerRadius(localDeviceCorner, localDeviceCorner),
                        style = stroke,
                    )
                    val nodeCx = size.width * localTunnelStartX
                    val nodeCy = size.height * localTunnelY
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(nodeCx, nodeCy),
                        radius = size.minDimension * localNodeRadius,
                    )
                    // Tunnel stub leaving the node toward the device edge.
                    drawLine(
                        color = colors.foreground,
                        start = Offset(nodeCx, nodeCy),
                        end = Offset(size.width * localTunnelEndX, nodeCy),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
                    )
                    // Closed terminator tick — the tunnel does not leave for the cloud.
                    val tickX = size.width * (localTunnelEndX - localTunnelTickInset)
                    drawLine(
                        color = colors.foreground,
                        start = Offset(tickX, nodeCy - size.height * localTunnelTickHeight),
                        end = Offset(tickX, nodeCy + size.height * localTunnelTickHeight),
                        strokeWidth = strokeWidth.toPx(),
                        cap = StrokeCap.Round,
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
                    val topPath =
                        Path().apply {
                            moveTo(srcX, midY)
                            quadraticTo(size.width * bypassCtrlXNear, topY, size.width * bypassMidY, topY)
                            quadraticTo(size.width * bypassCtrlXFar, topY, dstX, midY)
                        }
                    drawPath(path = topPath, color = colors.foreground, style = stroke)
                    val botPath =
                        Path().apply {
                            moveTo(srcX, midY)
                            quadraticTo(size.width * bypassCtrlXNear, botY, size.width * bypassMidY, botY)
                            quadraticTo(size.width * bypassCtrlXFar, botY, dstX, midY)
                        }
                    drawPath(path = botPath, color = colors.foreground, style = stroke)
                }

                OnboardingIllustration.Privacy -> {
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
                    drawCircle(
                        color = colors.foreground,
                        center = Offset(size.width * eyeMidX, eyeY),
                        radius = size.minDimension * eyePupilRadius,
                        style = stroke,
                    )
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

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingScreenPreview() {
    RipDpiTheme(themePreference = "light") {
        OnboardingScreenPreviewBody(OnboardingUiState(currentPage = 0, totalPages = OnboardingPages.size))
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingScreenSetupPreview() {
    RipDpiTheme(themePreference = "light") {
        OnboardingScreenPreviewBody(
            OnboardingUiState(
                currentPage = OnboardingInfoPageCount,
                totalPages = OnboardingPages.size,
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        OnboardingScreenPreviewBody(OnboardingUiState(currentPage = 2, totalPages = OnboardingPages.size))
    }
}

@Composable
private fun OnboardingScreenPreviewBody(uiState: OnboardingUiState) {
    OnboardingScreen(
        uiState = uiState,
        onPageChanged = {},
        onSkip = {},
        onContinue = {},
        onModeSelected = {},
        onDnsSelected = {},
        onOpenAdvancedDns = {},
        onRunValidation = {},
        onFinishKeepingRunning = {},
        onFinishDisconnected = {},
        onFinishAnyway = {},
        onAcceptSuggestedMode = {},
        onChangeDns = {},
    )
}
