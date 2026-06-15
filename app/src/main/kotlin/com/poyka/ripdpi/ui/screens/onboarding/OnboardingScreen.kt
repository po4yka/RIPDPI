package com.poyka.ripdpi.ui.screens.onboarding

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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

// Crossfade keyframe fractions for the swipeable info page. RDS bans per-element
// parallax / rotation / scale, so the page-swipe entrance is a pure alpha crossfade.
private const val alphaTextMin = 0.24f
private const val alphaTextRange = 0.76f
private const val alphaBodyMin = 0.18f
private const val alphaBodyRange = 0.82f
private const val alphaIllusMin = 0.4f
private const val alphaIllusRange = 0.6f

// Intro illustration is drawn container-less at this multiple of the base illustration size.
private const val introIllustrationScale = 1.5f

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
            OnboardingTopBar(skipVisible = skipVisible, onSkip = actions.onSkip)

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
            TextButton(
                onClick = onFinishDisconnected,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = introLayout.footerButtonMinHeight)
                        .ripDpiTestTag(RipDpiTestTags.OnboardingFinishDisconnected),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_validation_finish_disconnected),
                    style = type.introAction,
                    color = colors.mutedForeground,
                )
            }
        }
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

/** Per-frame crossfade alphas for the swipeable info page, derived from the page offset. */
private class OnboardingInfoCrossfade(
    val illustrationAlpha: Float,
    val titleAlpha: Float,
    val bodyAlpha: Float,
)

private fun onboardingInfoCrossfade(pageOffset: Float): OnboardingInfoCrossfade {
    val pageProgress = (1f - pageOffset.coerceIn(-1f, 1f).absoluteValue).coerceIn(0f, 1f)
    return OnboardingInfoCrossfade(
        illustrationAlpha = (alphaIllusMin + (pageProgress * alphaIllusRange)).coerceIn(0f, 1f),
        titleAlpha = (alphaTextMin + (pageProgress * alphaTextRange)).coerceIn(0f, 1f),
        bodyAlpha = (alphaBodyMin + (pageProgress * alphaBodyRange)).coerceIn(0f, 1f),
    )
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
    val crossfade = onboardingInfoCrossfade(pageOffset)

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
                    .graphicsLayer { alpha = crossfade.illustrationAlpha },
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
                    .graphicsLayer { alpha = crossfade.titleAlpha },
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
                    .graphicsLayer { alpha = crossfade.bodyAlpha },
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        OnboardingGuaranteeGrid(
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

/**
 * Passive guarantee grid in two grouped columns — privacy promises on the left, local-engine
 * capabilities on the right — each a small check mark + muted label. Read-only by design:
 * deliberately NOT bordered pills, so it never reads as an interactive filter-chip row.
 */
@Composable
private fun OnboardingGuaranteeGrid(
    privacyLabels: List<Int>,
    localLabels: List<Int>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        OnboardingGuaranteeColumn(labels = privacyLabels, modifier = Modifier.weight(1f))
        OnboardingGuaranteeColumn(labels = localLabels, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OnboardingGuaranteeColumn(
    labels: List<Int>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
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
    val balanced = pageModel.kind != SetupPageKind.DnsSelection
    val headerToContentGap = introLayout.setupHeaderToContentGap

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = introLayout.bodyHorizontalPadding),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                if (balanced) {
                    Arrangement.spacedBy(headerToContentGap, Alignment.CenterVertically)
                } else {
                    Arrangement.spacedBy(headerToContentGap)
                },
        ) {
            OnboardingSetupHeader(
                titleRes = pageModel.titleRes,
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
