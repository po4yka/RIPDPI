package com.poyka.ripdpi.ui.screens.onboarding

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.poyka.ripdpi.activities.OnboardingValidationRecoveryKind
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

// No-cloud mark — a struck circle to the upper right of the device ("no cloud, nothing leaves").
private const val noCloudCx = 0.82f
private const val noCloudCy = 0.20f
private const val noCloudRadius = 0.09f
private const val noCloudSlashSpan = 1.3f

// Intro illustration is drawn container-less at this multiple of the base illustration size.
private const val introIllustrationScale = 1.5f

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
    val layout = RipDpiThemeTokens.layout
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

    // Top action is reserved for the intro only ("Skip setup"). Inner steps rely on safe defaults +
    // the bottom CTA; the connection test keeps "Skip test" as a bottom secondary action instead.
    val skipVisible = settledPage == 0
    val showIdleSkipTest = isLastPage && validationState is OnboardingValidationState.Idle

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
            // 1) TOP BAR — status inset; intro-only "Skip setup"
            OnboardingTopBar(skipVisible = skipVisible, onSkip = onSkip)

            // 2) CONTENT — weighted; each page renders its own title at the top
            OnboardingPagerContent(
                pagerState = pagerState,
                validationBusy = validationBusy,
                uiState = uiState,
                onModeSelected = onModeSelected,
                onDnsSelected = onDnsSelected,
                onOpenAdvancedDns = onOpenAdvancedDns,
                onAcceptSuggestedMode = onAcceptSuggestedMode,
                onChangeDns = onChangeDns,
                onFinishDisconnected = onFinishDisconnected,
                onFinishAnyway = onFinishAnyway,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // 3) BOTTOM BAR — nav inset, page indicator + full-width primary CTA
            OnboardingBottomBar(
                settledPage = settledPage,
                pageCount = pageCount,
                isLastPage = isLastPage,
                continueLabelRes = currentPage.buttonLabelRes,
                validationState = validationState,
                showIdleSkipTest = showIdleSkipTest,
                onContinue = onContinue,
                onRunValidation = onRunValidation,
                onFinishKeepingRunning = onFinishKeepingRunning,
                onFinishAnyway = onFinishAnyway,
            )
        }
    }
}

/** The weighted, swipeable content region: one page per [OnboardingPages] entry. */
@Composable
private fun OnboardingPagerContent(
    pagerState: PagerState,
    validationBusy: Boolean,
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
    Box(modifier = modifier) {
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
}

/** Status-inset top bar. The skip action ("Skip setup") is shown on the intro page only. */
@Composable
private fun OnboardingTopBar(
    skipVisible: Boolean,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(introLayout.topActionRowHeight),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (skipVisible) {
            TextButton(
                onClick = onSkip,
                modifier =
                    Modifier
                        .ripDpiTestTag(RipDpiTestTags.OnboardingSkip)
                        .height(introLayout.topActionRowHeight),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip_setup),
                    style = type.introAction,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

/**
 * Nav-inset bottom bar: page indicator + full-width primary CTA, plus an optional bottom secondary
 * ("Skip test") on the idle connection-test page.
 */
@Composable
private fun OnboardingBottomBar(
    settledPage: Int,
    pageCount: Int,
    isLastPage: Boolean,
    continueLabelRes: Int,
    validationState: OnboardingValidationState,
    showIdleSkipTest: Boolean,
    onContinue: () -> Unit,
    onRunValidation: () -> Unit,
    onFinishKeepingRunning: () -> Unit,
    onFinishAnyway: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    Column(
        modifier =
            modifier
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
            continueLabelRes = continueLabelRes,
            validationState = validationState,
            onContinue = onContinue,
            onRunValidation = onRunValidation,
            onFinishKeepingRunning = onFinishKeepingRunning,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = introLayout.footerButtonMinHeight),
        )
        if (showIdleSkipTest) {
            TextButton(
                onClick = onFinishAnyway,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = introLayout.footerButtonMinHeight)
                        .ripDpiTestTag(RipDpiTestTags.OnboardingSkip),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip_test),
                    style = type.introAction,
                    color = colors.mutedForeground,
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
            val grantPermission =
                validationState.recoveryKind == OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION
            RipDpiButton(
                text =
                    stringResource(
                        if (grantPermission) {
                            R.string.onboarding_test_grant_permission
                        } else {
                            R.string.onboarding_test_retry
                        },
                    ),
                onClick = onRunValidation,
                leadingIcon = if (grantPermission) RipDpiIcons.Lock else null,
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = introLayout.bodyHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OnboardingIllustrationBox(
            modifier =
                Modifier
                    .size(introLayout.illustrationSize * introIllustrationScale)
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
        OnboardingGuaranteeGrid(
            labels =
                listOf(
                    R.string.onboarding_chip_no_account,
                    R.string.onboarding_chip_no_telemetry,
                    R.string.onboarding_chip_no_cloud_sync,
                    R.string.onboarding_chip_local_vpn,
                    R.string.onboarding_chip_local_proxy,
                    R.string.onboarding_chip_local_config,
                ),
        )
    }
}

/**
 * Passive 2-column guarantee grid: each cell is a small check mark + label in muted text. Read-only
 * by design — deliberately NOT bordered pills, so it never reads as an interactive filter-chip row.
 */
@Composable
private fun OnboardingGuaranteeGrid(
    labels: List<Int>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        labels.chunked(2).forEach { rowLabels ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                rowLabels.forEach { labelRes ->
                    OnboardingGuaranteeItem(
                        text = stringResource(labelRes),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowLabels.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OnboardingGuaranteeItem(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val components = RipDpiThemeTokens.components
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = RipDpiIcons.Check,
            contentDescription = null,
            tint = colors.mutedForeground,
            modifier = Modifier.size(components.inputs.chipIconSize),
        )
        Text(
            text = text,
            style = type.secondaryBody,
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

@Composable
private fun OnboardingIllustrationBox(modifier: Modifier = Modifier) {
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

/** Device outline + local node + contained tunnel stub + a struck "no cloud" mark. */
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
    val nodeCx = size.width * localTunnelStartX
    val nodeCy = size.height * localTunnelY
    drawCircle(color = color, center = Offset(nodeCx, nodeCy), radius = size.minDimension * localNodeRadius)
    drawLine(
        color = color,
        start = Offset(nodeCx, nodeCy),
        end = Offset(size.width * localTunnelEndX, nodeCy),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )
    val tickX = size.width * (localTunnelEndX - localTunnelTickInset)
    drawLine(
        color = color,
        start = Offset(tickX, nodeCy - size.height * localTunnelTickHeight),
        end = Offset(tickX, nodeCy + size.height * localTunnelTickHeight),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )
    val cloudCx = size.width * noCloudCx
    val cloudCy = size.height * noCloudCy
    val cloudR = size.minDimension * noCloudRadius
    drawCircle(color = color, center = Offset(cloudCx, cloudCy), radius = cloudR, style = stroke)
    drawLine(
        color = color,
        start = Offset(cloudCx - cloudR * noCloudSlashSpan, cloudCy + cloudR * noCloudSlashSpan),
        end = Offset(cloudCx + cloudR * noCloudSlashSpan, cloudCy - cloudR * noCloudSlashSpan),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round,
    )
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
