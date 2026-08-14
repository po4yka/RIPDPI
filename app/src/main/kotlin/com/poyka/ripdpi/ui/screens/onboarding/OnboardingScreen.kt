package com.poyka.ripdpi.ui.screens.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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
import com.poyka.ripdpi.activities.mapNotificationPermissionResult
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiTextAction
import com.poyka.ripdpi.ui.components.indicators.RipDpiPageIndicators
import com.poyka.ripdpi.ui.components.intro.RipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.flow.Flow
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

// Intro illustration is drawn container-less at this multiple of the base illustration size.
private const val introIllustrationScale = 2.1f

// Parallax travel fractions (× illustration size) for the page-swipe entrance animation.
private const val illusTravelFraction = 0.55f
private const val titleTravelFraction = 0.35f
private const val bodyTravelFraction = 0.52f
private const val illusLiftFraction = 0.15f

// Fraction of the page viewport reserved BELOW the guarantee grid in the SpaceBetween info
// layout. Larger value lifts the grid toward the body, shrinking the body->grid empty band.
private const val guaranteeGridBottomInsetFraction = 0.12f
private const val AccessibilityOnboardingFontScale = 1.5f

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
            val shouldShowRationale =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                        ?: true
                } else {
                    true
                }
            viewModel.onNotificationPermissionResult(
                result = mapNotificationPermissionResult(granted, shouldShowRationale),
            )
        }
    val notificationsSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onNotificationPermissionResult(PermissionResult.ReturnedFromSettings)
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
        onRequestNotificationsSettings = notificationsSettingsLauncher::launch,
        onRequestVpnConsent = vpnConsentLauncher::launch,
    )

    OnboardingScreen(
        uiState = uiState,
        modifier = modifier,
        actions =
            OnboardingScreenActions(
                onPageChanged = remember(viewModel) { viewModel::setCurrentPage },
                onSkip = remember(viewModel) { viewModel::skip },
                onContinue = remember(viewModel) { viewModel::nextPage },
                onModeSelected = remember(viewModel) { viewModel::selectMode },
                onPersonaSelected = remember(viewModel) { viewModel::selectPersona },
                onDnsSelected = remember(viewModel) { viewModel::selectDnsProvider },
                onOpenAdvancedDns = onOpenAdvancedDns,
                onRunValidation = remember(viewModel) { viewModel::runValidation },
                onFinishDisconnected = remember(viewModel) { viewModel::finishDisconnected },
                onFinishKeepRunning = remember(viewModel) { viewModel::finishKeepingRunning },
                onFinishAnyway = remember(viewModel) { viewModel::finishAnyway },
                onAcceptSuggestedMode = remember(viewModel) { viewModel::acceptSuggestedMode },
                onChangeDns = onOpenAdvancedDns,
            ),
    )
}

@Composable
internal fun OnboardingEffectsHandler(
    effects: Flow<OnboardingEffect>,
    onComplete: () -> Unit,
    onRequestNotificationsPermission: () -> Unit,
    onRequestNotificationsSettings: (Intent) -> Unit,
    onRequestVpnConsent: (Intent) -> Unit,
) {
    LifecycleEventEffect(effects) { effect ->
        when (effect) {
            OnboardingEffect.OnboardingComplete -> onComplete()
            OnboardingEffect.RequestNotificationsPermission -> onRequestNotificationsPermission()
            is OnboardingEffect.RequestNotificationsSettings -> onRequestNotificationsSettings(effect.intent)
            is OnboardingEffect.RequestVpnConsent -> onRequestVpnConsent(effect.intent)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

internal data class OnboardingScreenActions(
    val onPageChanged: (Int) -> Unit = {},
    val onSkip: () -> Unit = {},
    val onContinue: () -> Unit = {},
    val onModeSelected: (Mode) -> Unit = {},
    val onPersonaSelected: (String) -> Unit = {},
    val onDnsSelected: (String) -> Unit = {},
    val onOpenAdvancedDns: () -> Unit = {},
    val onRunValidation: () -> Unit = {},
    val onFinishDisconnected: () -> Unit = {},
    val onFinishKeepRunning: () -> Unit = {},
    val onFinishAnyway: () -> Unit = {},
    val onAcceptSuggestedMode: () -> Unit = {},
    val onChangeDns: () -> Unit = {},
)

@Composable
internal fun OnboardingScreen(
    uiState: OnboardingUiState,
    actions: OnboardingScreenActions,
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
            actions.onPageChanged(pagerState.settledPage)
        }
    }

    val settledPage = pagerState.settledPage.coerceIn(0, OnboardingPages.lastIndex)
    val currentPage = OnboardingPages[settledPage]
    val isLastPage = settledPage == OnboardingPages.lastIndex
    val validationState = uiState.validationState
    val validationBusy = validationState.isBusy
    val pageCount = uiState.totalPages.coerceAtMost(OnboardingPages.size)
    val useAccessibilityLayout = LocalDensity.current.fontScale >= AccessibilityOnboardingFontScale

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
            OnboardingTopBar(
                skipVisible = skipVisible,
                collapseEmptySpace = useAccessibilityLayout && !skipVisible,
                onSkip = actions.onSkip,
            )

            // 2) CONTENT — weighted; each page renders its own title at the top
            OnboardingPagerContent(
                pagerState = pagerState,
                validationBusy = validationBusy,
                uiState = uiState,
                actions = actions,
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
                onContinue = actions.onContinue,
                onRunValidation = actions.onRunValidation,
                onFinishDisconnected = actions.onFinishDisconnected,
                onFinishKeepRunning = actions.onFinishKeepRunning,
                onFinishAnyway = actions.onFinishAnyway,
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
    actions: OnboardingScreenActions,
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
                        onModeSelected = actions.onModeSelected,
                        onPersonaSelected = actions.onPersonaSelected,
                        onDnsSelected = actions.onDnsSelected,
                        onOpenAdvancedDns = actions.onOpenAdvancedDns,
                        onAcceptSuggestedMode = actions.onAcceptSuggestedMode,
                        onChangeDns = actions.onChangeDns,
                        onFinishAnyway = actions.onFinishAnyway,
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
    collapseEmptySpace: Boolean,
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
                .height(if (collapseEmptySpace) 0.dp else introLayout.topActionRowHeight),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (skipVisible) {
            RipDpiTextAction(
                text = stringResource(R.string.onboarding_skip_setup),
                onClick = onSkip,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingSkip),
                textStyle = type.introAction,
                color = colors.mutedForeground,
                minHeight = introLayout.topActionRowHeight,
            )
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
    onFinishDisconnected: () -> Unit,
    onFinishKeepRunning: () -> Unit,
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
            onFinishKeepRunning = onFinishKeepRunning,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = introLayout.footerButtonMinHeight),
        )
        if (validationState is OnboardingValidationState.Success) {
            RipDpiTextAction(
                text = stringResource(R.string.onboarding_validation_finish_disconnected),
                onClick = onFinishDisconnected,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.OnboardingFinishDisconnected),
                textStyle = type.introAction,
                color = colors.mutedForeground,
                minHeight = introLayout.footerButtonMinHeight,
            )
        }
        if (showIdleSkipTest) {
            RipDpiTextAction(
                text = stringResource(R.string.onboarding_skip_test),
                onClick = onFinishAnyway,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.OnboardingSkipTest),
                textStyle = type.introAction,
                color = colors.mutedForeground,
                minHeight = introLayout.footerButtonMinHeight,
            )
        }
    }
}

/**
 * State-driven footer primary CTA. Identical full-width pattern across all 4 pages.
 * On pages 0–2 it is the page's Continue/Next action; on the ConnectionTest page it is driven by
 * [validationState] (Start test / Testing… disabled / Finish disconnected / Retry).
 */
@Composable
private fun OnboardingFooterCta(
    isLastPage: Boolean,
    continueLabelRes: Int,
    validationState: OnboardingValidationState,
    onContinue: () -> Unit,
    onRunValidation: () -> Unit,
    onFinishKeepRunning: () -> Unit,
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
                text = stringResource(R.string.onboarding_validation_finish_keep_running),
                onClick = onFinishKeepRunning,
                modifier = modifier.ripDpiTestTag(RipDpiTestTags.OnboardingFinishKeepRunning),
            )
        }

        is OnboardingValidationState.Failed -> {
            val grantPermission = validationState.recoveryKind.requiresPermissionGrantAction()
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

private fun OnboardingValidationRecoveryKind.requiresPermissionGrantAction(): Boolean =
    this == OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION ||
        this == OnboardingValidationRecoveryKind.REQUEST_NOTIFICATION_SETTINGS

/** Per-frame parallax + fade values for the swipeable info page, derived once from the page offset. */
private class OnboardingInfoParallax(
    val clampedOffset: Float,
    val pageProgress: Float,
    val illustrationTravelPx: Float,
    val titleTravelPx: Float,
    val bodyTravelPx: Float,
    val illustrationLiftPx: Float,
    val textAlpha: Float,
    val bodyAlpha: Float,
)

@Composable
private fun onboardingInfoParallax(
    pageOffset: Float,
    illustrationSize: Dp,
): OnboardingInfoParallax {
    val clampedOffset = pageOffset.coerceIn(-1f, 1f)
    val pageProgress = (1f - clampedOffset.absoluteValue).coerceIn(0f, 1f)
    return with(LocalDensity.current) {
        OnboardingInfoParallax(
            clampedOffset = clampedOffset,
            pageProgress = pageProgress,
            illustrationTravelPx = (illustrationSize * illusTravelFraction).toPx(),
            titleTravelPx = (illustrationSize * titleTravelFraction).toPx(),
            bodyTravelPx = (illustrationSize * bodyTravelFraction).toPx(),
            illustrationLiftPx = (illustrationSize * illusLiftFraction).toPx(),
            textAlpha = (alphaTextMin + (pageProgress * alphaTextRange)).coerceIn(0f, 1f),
            bodyAlpha = (alphaBodyMin + (pageProgress * alphaBodyRange)).coerceIn(0f, 1f),
        )
    }
}

@Composable
private fun OnboardingInfoPageScene(
    pageModel: OnboardingPage.Informational,
    pageOffset: Float,
    modifier: Modifier = Modifier,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val parallax = onboardingInfoParallax(pageOffset, introLayout.illustrationSize)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Captured here so it stays reachable inside the nested Column lambdas — Compose's
        // @LayoutScopeMarker hides the outer BoxWithConstraints receiver from inner layout scopes.
        val gridBottomInset =
            if (LocalDensity.current.fontScale >= AccessibilityOnboardingFontScale) {
                0.dp
            } else {
                maxHeight * guaranteeGridBottomInsetFraction
            }
        // Two-zone layout: the hero cluster (illustration + title + body) anchors the top and the
        // guarantee grid is pushed toward the CTA by SpaceBetween, so the page reads as two
        // deliberate zones instead of a top-loaded block over an empty band. heightIn(min =
        // maxHeight) keeps the column at least one viewport tall so SpaceBetween has room to
        // distribute, while verticalScroll preserves large-font / a11y reflow on short viewports.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = introLayout.bodyHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            OnboardingInfoHero(
                pageModel = pageModel,
                parallax = parallax,
                introLayout = introLayout,
            )
            OnboardingGuaranteeGrid(
                modifier = Modifier.padding(bottom = gridBottomInset),
                privacyLabels =
                    listOf(
                        R.string.onboarding_chip_no_account,
                        R.string.onboarding_chip_no_telemetry,
                        R.string.onboarding_chip_no_cloud_sync,
                    ),
                localLabels =
                    listOf(
                        R.string.onboarding_chip_local_vpn,
                        R.string.onboarding_chip_local_proxy,
                        R.string.onboarding_chip_local_config,
                    ),
            )
        }
    }
}

/**
 * Hero cluster for the informational onboarding page — illustration, title, and body — extracted
 * so [OnboardingInfoPageScene] stays within the method-length budget. Kept as one unit so the
 * caller's SpaceBetween arrangement treats it as the single top zone.
 */
@Composable
private fun OnboardingInfoHero(
    pageModel: OnboardingPage.Informational,
    parallax: OnboardingInfoParallax,
    introLayout: RipDpiIntroScaffoldMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    // A leading inset clears the OnboardingTopBar "Skip setup" row that overlays the page.
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(
            modifier =
                Modifier.height(
                    if (LocalDensity.current.fontScale >= AccessibilityOnboardingFontScale) {
                        0.dp
                    } else {
                        introLayout.topActionRowHeight
                    },
                ),
        )
        OnboardingIllustrationBox(
            modifier =
                Modifier
                    .size(introLayout.illustrationSize * introIllustrationScale)
                    .graphicsLayer {
                        translationX = -parallax.clampedOffset * parallax.illustrationTravelPx
                        translationY = (1f - parallax.pageProgress) * parallax.illustrationLiftPx
                        rotationZ = parallax.clampedOffset * 2f
                        scaleX = scaleIllusBase + (parallax.pageProgress * scaleIllusRange)
                        scaleY = scaleIllusBase + (parallax.pageProgress * scaleIllusRange)
                        alpha = (alphaIllusMin + (parallax.pageProgress * alphaIllusRange)).coerceIn(0f, 1f)
                    },
        )
        Spacer(modifier = Modifier.height(spacing.xl))
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
                        translationX = parallax.clampedOffset * parallax.titleTravelPx
                        alpha = parallax.textAlpha
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
                    .graphicsLayer {
                        translationX = parallax.clampedOffset * parallax.bodyTravelPx
                        alpha = parallax.bodyAlpha
                    },
        )
    }
}

/**
 * Passive guarantee grid in two grouped columns — privacy promises on the left, local-engine
 * capabilities on the right — each a small check mark + muted label. Each column carries a quiet
 * micro-header ("Privacy" / "On your device") so the grouping reads. Read-only by design:
 * deliberately NOT bordered pills, so it never reads as an interactive filter-chip row.
 */
@Composable
private fun OnboardingGuaranteeGrid(
    privacyLabels: List<Int>,
    localLabels: List<Int>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val useAccessibilityLayout = LocalDensity.current.fontScale >= AccessibilityOnboardingFontScale
    val gridModifier =
        modifier
            .widthIn(max = introLayout.guaranteeGridMaxWidth)
            .fillMaxWidth()
    if (useAccessibilityLayout) {
        Column(
            modifier = gridModifier,
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            OnboardingGuaranteeColumn(
                headerRes = R.string.onboarding_guarantee_header_privacy,
                labels = privacyLabels,
                modifier = Modifier.fillMaxWidth(),
            )
            OnboardingGuaranteeColumn(
                headerRes = R.string.onboarding_guarantee_header_local,
                labels = localLabels,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(
            modifier = gridModifier,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            OnboardingGuaranteeColumn(
                headerRes = R.string.onboarding_guarantee_header_privacy,
                labels = privacyLabels,
                modifier = Modifier.weight(1f),
            )
            OnboardingGuaranteeColumn(
                headerRes = R.string.onboarding_guarantee_header_local,
                labels = localLabels,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OnboardingGuaranteeColumn(
    @StringRes headerRes: Int,
    labels: List<Int>,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(headerRes),
            style = type.caption,
            color = colors.mutedForeground,
            modifier = Modifier.fillMaxWidth(),
        )
        labels.forEach { labelRes ->
            OnboardingGuaranteeItem(
                text = stringResource(labelRes),
                modifier = Modifier.fillMaxWidth(),
            )
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
            // Constrain to the remaining column width so long translated labels wrap, never clip.
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One coherent setup step: a header (title + optional subtitle) followed by the step content, laid
 * out as a single scrollable group. Short steps (mode, connection test) are vertically balanced
 * between the top bar and the bottom action area; the longer DNS list is top-aligned and scrolls.
 * The outer [Column] supplies the bounded weight slot so centering works while overflow still
 * scrolls at large font scales.
 */
@Composable
private fun OnboardingSetupPageScene(
    pageModel: OnboardingPage.Setup,
    uiState: OnboardingUiState,
    onModeSelected: (Mode) -> Unit,
    onPersonaSelected: (String) -> Unit,
    onDnsSelected: (String) -> Unit,
    onOpenAdvancedDns: () -> Unit,
    onAcceptSuggestedMode: () -> Unit,
    onChangeDns: () -> Unit,
    onFinishAnyway: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val useAccessibilityLayout = LocalDensity.current.fontScale >= AccessibilityOnboardingFontScale
    val balanced = !useAccessibilityLayout && pageModel.kind != SetupPageKind.DnsSelection
    val headerToContentGap = introLayout.setupHeaderToContentGap
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = introLayout.bodyHorizontalPadding),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = if (useAccessibilityLayout) spacing.md else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                if (balanced) {
                    Arrangement.spacedBy(headerToContentGap, Alignment.CenterVertically)
                } else {
                    Arrangement.spacedBy(headerToContentGap)
                },
        ) {
            val headerTitleRes =
                if (pageModel.kind == SetupPageKind.ConnectionTest &&
                    uiState.validationState is OnboardingValidationState.Success
                ) {
                    R.string.onboarding_test_success_title
                } else {
                    pageModel.titleRes
                }
            OnboardingSetupHeader(
                titleRes = headerTitleRes,
                subtitleRes = onboardingSetupSubtitleRes(pageModel.kind),
            )

            when (pageModel.kind) {
                SetupPageKind.PersonaSelection -> {
                    OnboardingPersonaSelectionContent(
                        selectedPersona = uiState.selectedPersona,
                        onPersonaSelected = onPersonaSelected,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SetupPageKind.ModeSelection -> {
                    OnboardingModeSelectionContent(
                        selectedMode = uiState.selectedMode,
                        onModeSelected = onModeSelected,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SetupPageKind.DnsSelection -> {
                    OnboardingDnsSelectionContent(
                        selectedProviderId = uiState.selectedDnsProviderId,
                        onDnsSelected = onDnsSelected,
                        onOpenAdvancedDns = onOpenAdvancedDns,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SetupPageKind.ConnectionTest -> {
                    OnboardingModeValidationContent(
                        uiState = uiState,
                        onAcceptSuggestedMode = onAcceptSuggestedMode,
                        onChangeDns = onChangeDns,
                        onFinishAnyway = onFinishAnyway,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Centered header for a setup step: the page title plus an optional one-line subtitle. */
@Composable
private fun OnboardingSetupHeader(
    titleRes: Int,
    subtitleRes: Int?,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(titleRes),
            style = type.introTitle,
            color = colors.foreground,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = introLayout.titleHorizontalPadding),
        )
        subtitleRes?.let { res ->
            Text(
                text = stringResource(res),
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

/** Optional one-line subtitle shown under a setup step's title. */
private fun onboardingSetupSubtitleRes(kind: SetupPageKind): Int? =
    when (kind) {
        SetupPageKind.PersonaSelection -> R.string.onboarding_persona_body
        SetupPageKind.ModeSelection -> R.string.onboarding_setup_mode_subtitle
        SetupPageKind.DnsSelection -> null
        SetupPageKind.ConnectionTest -> null
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
        actions = OnboardingScreenActions(),
    )
}
