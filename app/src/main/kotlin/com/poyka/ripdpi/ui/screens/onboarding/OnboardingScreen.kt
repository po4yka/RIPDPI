package com.poyka.ripdpi.ui.screens.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.mapNotificationPermissionResult
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiTextAction
import com.poyka.ripdpi.ui.components.indicators.RipDpiPageIndicators
import com.poyka.ripdpi.ui.components.intro.rememberRipDpiIntroScaffoldMetrics
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.status.StatusFirstRunIntroStore
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

private const val AccessibilityOnboardingFontScale = 1.5f

// Below this window height (a phone in landscape) the step badge moves into the top bar.
private val OnboardingCompactHeight = 480.dp

// Share of the page width that a page gives up while it scrolls, so its content moves less than the finger.
private const val OnboardingPageParallax = 0.4f

// Blur of a page that is one full step away. A page at rest has no blur and no layer effect.
private val OnboardingPageBlur = 8.dp

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
        onComplete = {
            // The main screen explains itself once to a user who has just completed onboarding.
            StatusFirstRunIntroStore.setPending(context, pending = true)
            onComplete()
        },
        onRequestNotificationsPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.onNotificationPermissionResult(PermissionResult.Granted)
            }
        },
        onRequestNotificationsSettings = notificationsSettingsLauncher::launch,
        onRequestVpnConsent = vpnConsentLauncher::launch,
        onOpenSystemSettings = { intent ->
            // A device without that settings screen leaves the user on the step, which stays complete without it.
            runCatching { context.startActivity(intent) }
        },
    )

    OnboardingScreen(
        uiState = uiState,
        modifier = modifier,
        actions =
            OnboardingScreenActions(
                onPageChanged = remember(viewModel) { viewModel::setCurrentPage },
                onBack = remember(viewModel) { viewModel::previousPage },
                onShowCompletion = remember(viewModel) { { viewModel.setCurrentPage(OnboardingCompletionPageIndex) } },
                onDnsSelected = remember(viewModel) { viewModel::selectDnsProvider },
                onOpenBatterySettings = remember(viewModel) { { viewModel.openSystemSettings(vpn = false) } },
                onOpenVpnSettings = remember(viewModel) { { viewModel.openSystemSettings(vpn = true) } },
                onFinish = remember(viewModel) { viewModel::finish },
                onFinishDisconnected = remember(viewModel) { viewModel::finishDisconnected },
                onContinue =
                    remember(viewModel) { { viewModel.setCurrentPage(viewModel.uiState.value.currentPage + 1) } },
                onModeSelected = remember(viewModel) { viewModel::selectMode },
                onPersonaSelected = remember(viewModel) { viewModel::selectPersona },
                onRunValidation = remember(viewModel) { viewModel::runValidation },
                onFinishKeepRunning = remember(viewModel) { viewModel::finishKeepingRunning },
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
    onOpenSystemSettings: (Intent) -> Unit = {},
) {
    LifecycleEventEffect(effects) { effect ->
        when (effect) {
            OnboardingEffect.OnboardingComplete -> onComplete()
            OnboardingEffect.RequestNotificationsPermission -> onRequestNotificationsPermission()
            is OnboardingEffect.RequestNotificationsSettings -> onRequestNotificationsSettings(effect.intent)
            is OnboardingEffect.RequestVpnConsent -> onRequestVpnConsent(effect.intent)
            is OnboardingEffect.OpenSystemSettings -> onOpenSystemSettings(effect.intent)
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
    val onBack: () -> Unit = {},
    val onShowCompletion: () -> Unit = {},
    val onDnsSelected: (String) -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
    val onOpenVpnSettings: () -> Unit = {},
    val onFinish: () -> Unit = {},
    val onFinishDisconnected: () -> Unit = {},
    val onContinue: () -> Unit = {},
    val onModeSelected: (Mode) -> Unit = {},
    val onPersonaSelected: (String) -> Unit = {},
    val onRunValidation: () -> Unit = {},
    val onFinishKeepRunning: () -> Unit = {},
    val onAcceptSuggestedMode: () -> Unit = {},
    val onChangeDns: () -> Unit = {},
)

@Composable
internal fun OnboardingScreen(
    uiState: OnboardingUiState,
    actions: OnboardingScreenActions,
    modifier: Modifier = Modifier,
) {
    val layout = RipDpiThemeTokens.layout
    val pagerState = rememberOnboardingPagerState(uiState.currentPage, actions.onPageChanged)
    val scrub = RipDpiThemeTokens.motion.animationsEnabled
    val pagePosition = rememberOnboardingPagePosition(pagerState, scrub)

    val settledPage = pagerState.settledPage.coerceIn(0, OnboardingPages.lastIndex)
    val currentPage = OnboardingPages[settledPage]
    val isTestPage = settledPage == OnboardingTestPageIndex
    val isCompletionPage = settledPage == OnboardingCompletionPageIndex
    val validationState = uiState.validationState
    val validationBusy = validationState.isBusy
    val validationSucceeded = validationState is OnboardingValidationState.Success
    val pageCount = uiState.totalPages.coerceAtMost(OnboardingPages.size)
    // The intro offers Skip; inner steps provide an explicit path to the previous selection.
    val skipVisible = settledPage == 0
    val skipTestVisible = isTestPage && validationState is OnboardingValidationState.Idle

    BackHandler(enabled = settledPage > 0) {
        // Keep the validation screen in place during its test and over the running tunnel it leaves behind.
        if (!validationBusy && !validationSucceeded) {
            actions.onBack()
        }
    }

    BoxWithConstraints(
        modifier =
            modifier
                .ripDpiAutomationTreeRoot()
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Onboarding))
                .fillMaxSize()
                .ripDpiScreenBackground(),
    ) {
        // A short and narrow window (split screen) keeps the plain top bar: its action needs the width.
        val compactHeight = maxHeight < OnboardingCompactHeight && maxWidth > maxHeight
        Column(
            modifier =
                Modifier
                    .widthIn(max = layout.formMaxWidth)
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .displayCutoutPadding()
                    .padding(horizontal = layout.horizontalPadding),
        ) {
            // 1) TOP BAR — status inset; inner-step Back and the step's optional text action.
            OnboardingTopBar(
                skipVisible = skipVisible,
                backEnabled = !validationBusy,
                skipTestVisible = skipTestVisible,
                finishDisconnectedVisible = validationSucceeded,
                // Skip setup and Skip test show the completion step, which completes onboarding.
                onFinish = actions.onShowCompletion,
                onFinishDisconnected = actions.onFinishDisconnected,
                onBack = actions.onBack,
                badgePosition = pagePosition.takeIf { compactHeight },
            )

            // 2) STEP BADGE — pinned: it stays in place while the pages change under it.
            if (!compactHeight) {
                // The completion step keeps the live line of a passed test. A failed test breaks the line on the
                // test step only.
                val onPath = isTestPage || (isCompletionPage && validationSucceeded)
                OnboardingPinnedBadge(pagePosition, onboardingSignal(validationState, onTestStep = onPath))
            }

            // 3) CONTENT — weighted; each page renders its own title at the top
            OnboardingPagerContent(
                pagerState = pagerState,
                validationBusy = validationBusy,
                uiState = uiState,
                actions = actions,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // 4) BOTTOM BAR — nav inset, page indicator + full-width primary CTA
            OnboardingBottomBar(
                settledPage = settledPage,
                // Flips halfway through a drag, so the highlight moves with the widths.
                indicatorPage = { if (scrub) pagerState.currentPage else settledPage },
                pagePosition = pagePosition,
                pageCount = pageCount,
                isTestPage = isTestPage,
                isCompletionPage = isCompletionPage,
                continueLabelRes = currentPage.buttonLabelRes,
                validationState = validationState,
                onContinue = actions.onContinue,
                onRunValidation = actions.onRunValidation,
                onFinishKeepRunning = actions.onFinishKeepRunning,
                onFinish = actions.onFinish,
            )
        }
    }
}

/**
 * The fractional pager position for the badge and the indicators. It is read in the layout and draw phases
 * only, so a drag between steps does not recompose the screen. Without animations it changes only when a step
 * settles.
 */
@Composable
private fun rememberOnboardingPagePosition(
    pagerState: PagerState,
    scrub: Boolean,
): () -> Float =
    remember(pagerState, scrub) {
        {
            if (scrub) {
                pagerState.currentPage + pagerState.currentPageOffsetFraction
            } else {
                pagerState.settledPage.toFloat()
            }
        }
    }

/**
 * The step badge in its pinned place above the pages, on the signal line that runs through every step. It does
 * not scroll, so at large font scales the badge is smaller.
 */
@Composable
private fun OnboardingPinnedBadge(
    pagePosition: () -> Float,
    signal: OnboardingSignal,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val largeFont = LocalDensity.current.fontScale >= AccessibilityOnboardingFontScale
    Box(
        modifier = modifier.fillMaxWidth().padding(bottom = spacing.md + spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        OnboardingSignalLine(signal = signal, pagePosition = pagePosition)
        OnboardingStepBadge(
            pagePosition = pagePosition,
            succeeded = signal.mode == RipDpiSignalMode.Live,
            size = if (largeFont) OnboardingStepBadgeCompactSize else OnboardingStepBadgeSize,
        )
    }
}

/** The pager follows the step in the state, and reports a step the user reached with a swipe. */
@Composable
private fun rememberOnboardingPagerState(
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
): PagerState {
    val pagerState =
        rememberPagerState(
            initialPage = currentPage.coerceIn(0, OnboardingPages.lastIndex),
            pageCount = { OnboardingPages.size },
        )

    LaunchedEffect(currentPage) {
        val targetPage = currentPage.coerceIn(0, OnboardingPages.lastIndex)
        if (targetPage != pagerState.settledPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    val performHaptic = rememberRipDpiHapticPerformer()
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != currentPage) {
            // Only a swipe settles on a step that the state does not hold yet. A button gives its own haptic.
            performHaptic(RipDpiHapticFeedback.Selection)
            onPageChanged(pagerState.settledPage)
        }
    }
    return pagerState
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
    val motion = RipDpiThemeTokens.motion
    val scrubPages = motion.animationsEnabled
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    // RenderEffect needs API 31. Below it a leaving page fades and moves, with no blur.
    val blurPages = scrubPages && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val pageBlur = with(LocalDensity.current) { OnboardingPageBlur.toPx() }
    // The first launch plays the entrance once; a rotation or a restored process does not repeat it.
    var entrancePlayed by rememberSaveable { mutableStateOf(false) }
    val entrance = remember { Animatable(if (entrancePlayed || !motion.animationsEnabled) 1f else 0f) }
    val entranceRise = with(LocalDensity.current) { RipDpiThemeTokens.spacing.lg.toPx() }
    LaunchedEffect(entrance) {
        if (entrance.value < 1f) entrance.animateTo(1f, motion.emphasizedTween())
        entrancePlayed = true
    }

    Box(
        modifier =
            modifier.graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * entranceRise
            },
    ) {
        HorizontalPager(
            state = pagerState,
            // The completion step is left with Back, which returns to the step that the user came from.
            userScrollEnabled =
                !validationBusy &&
                    uiState.validationState !is OnboardingValidationState.Success &&
                    pagerState.settledPage != OnboardingCompletionPageIndex,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            // A page fades and blurs with its distance from the pager position and moves less than the finger, so
            // the old and the new step cross over in place. Without animations the pager moves the pages unchanged.
            val pageModifier =
                if (scrubPages) {
                    Modifier.fillMaxSize().graphicsLayer {
                        val offset = page - (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        val distance = abs(offset).coerceIn(0f, 1f)
                        val blur = distance * pageBlur
                        alpha = 1f - distance
                        // The platform rejects a blur with no radius, so a page near rest has no effect at all.
                        renderEffect = if (blurPages && blur >= 1f) BlurEffect(blur, blur, TileMode.Decal) else null
                        translationX = -offset * size.width * OnboardingPageParallax * direction
                    }
                } else {
                    Modifier.fillMaxSize()
                }
            when (val pageModel = OnboardingPages[page]) {
                is OnboardingPage.Informational -> {
                    OnboardingInfoPageScene(
                        pageModel = pageModel,
                        modifier = pageModifier,
                    )
                }

                is OnboardingPage.Setup -> {
                    OnboardingSetupPageScene(
                        pageModel = pageModel,
                        uiState = uiState,
                        actions = actions,
                        modifier = pageModifier,
                    )
                }
            }
        }
    }
}

/**
 * Status-inset top bar: Back on later steps and the step's optional text action at the end. In a short window
 * it also holds the step badge, centered between two equal sides; [badgePosition] is then the pager position.
 */
@Composable
private fun OnboardingTopBar(
    skipVisible: Boolean,
    backEnabled: Boolean,
    skipTestVisible: Boolean,
    finishDisconnectedVisible: Boolean,
    onFinish: () -> Unit,
    onFinishDisconnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    badgePosition: (() -> Float)? = null,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    // After a successful test the tunnel runs: going back would leave it up under a mode choice.
    val backVisible = !skipVisible && !finishDisconnectedVisible
    val back = @Composable {
        if (backVisible) {
            RipDpiIconButton(
                icon = RipDpiIcons.Back,
                contentDescription = stringResource(R.string.navigation_back),
                onClick = onBack,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.OnboardingBack),
                enabled = backEnabled,
            )
        }
    }
    val action = @Composable { actionModifier: Modifier ->
        when {
            skipVisible -> {
                OnboardingTopBarAction(
                    R.string.onboarding_skip_setup,
                    onFinish,
                    RipDpiTestTags.OnboardingSkip,
                    actionModifier,
                )
            }

            skipTestVisible -> {
                OnboardingTopBarAction(
                    R.string.onboarding_skip_test,
                    onFinish,
                    RipDpiTestTags.OnboardingSkipTest,
                    actionModifier,
                )
            }

            finishDisconnectedVisible -> {
                OnboardingTopBarAction(
                    R.string.vpn_confirm_disconnect_disconnect,
                    onFinishDisconnected,
                    RipDpiTestTags.OnboardingFinishDisconnected,
                    actionModifier,
                )
            }
        }
    }
    val rowModifier =
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(introLayout.topActionRowHeight)
    if (badgePosition == null) {
        Row(
            modifier = rowModifier,
            horizontalArrangement = if (backVisible) Arrangement.SpaceBetween else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            back()
            action(Modifier.weight(1f, fill = false))
        }
    } else {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { back() }
            OnboardingStepBadge(
                pagePosition = badgePosition,
                succeeded = finishDisconnectedVisible,
                size = OnboardingStepBadgeCompactSize,
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { action(Modifier) }
        }
    }
}

@Composable
private fun OnboardingTopBarAction(
    @StringRes textRes: Int,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    RipDpiTextAction(
        text = stringResource(textRes),
        onClick = onClick,
        modifier = modifier.ripDpiTestTag(testTag),
        textStyle = RipDpiThemeTokens.type.introAction,
        color = RipDpiThemeTokens.colors.mutedForeground,
        minHeight = rememberRipDpiIntroScaffoldMetrics().topActionRowHeight,
    )
}

/** Nav-inset bottom bar: page indicator + full-width primary CTA. */
@Composable
private fun OnboardingBottomBar(
    settledPage: Int,
    indicatorPage: () -> Int,
    pagePosition: () -> Float,
    pageCount: Int,
    isTestPage: Boolean,
    isCompletionPage: Boolean,
    continueLabelRes: Int,
    validationState: OnboardingValidationState,
    onContinue: () -> Unit,
    onRunValidation: () -> Unit,
    onFinishKeepRunning: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // The top gap keeps scrolling page content clear of the indicators.
                .padding(top = introLayout.footerProgressGap, bottom = introLayout.footerBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Hidden, not removed, so the CTA stays in place when the test succeeds.
        val finished = validationState is OnboardingValidationState.Success
        Box(modifier = if (finished) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier) {
            RipDpiPageIndicators(
                currentPage = indicatorPage(),
                pagePosition = pagePosition,
                pageCount = pageCount,
                accessibilityLabel =
                    stringResource(
                        R.string.onboarding_step_progress,
                        settledPage + 1,
                        pageCount,
                    ),
            )
        }
        Spacer(modifier = Modifier.height(introLayout.footerProgressGap))
        OnboardingFooterCta(
            isTestPage = isTestPage,
            isCompletionPage = isCompletionPage,
            continueLabelRes = continueLabelRes,
            validationState = validationState,
            onContinue = onContinue,
            onRunValidation = onRunValidation,
            onFinishKeepRunning = onFinishKeepRunning,
            onFinish = onFinish,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = introLayout.footerButtonMinHeight),
        )
    }
}

/**
 * State-driven footer primary CTA. Identical full-width pattern across all pages.
 * On the ConnectionTest page it is driven by [validationState] (Start test / Testing… disabled / Done / Retry).
 * On the completion page it completes onboarding: it keeps a passed test running, and otherwise stops the test
 * runtime. On every other page it is the page's Continue action.
 */
@Composable
private fun OnboardingFooterCta(
    isTestPage: Boolean,
    isCompletionPage: Boolean,
    continueLabelRes: Int,
    validationState: OnboardingValidationState,
    onContinue: () -> Unit,
    onRunValidation: () -> Unit,
    onFinishKeepRunning: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isCompletionPage && validationState !is OnboardingValidationState.Success) {
        RipDpiButton(
            text = stringResource(continueLabelRes),
            onClick = onFinish,
            modifier = modifier.ripDpiTestTag(RipDpiTestTags.OnboardingCompletionDone),
        )
        return
    }
    // The completion page after a passed test shows the same Done as the test page, so it stays in place.
    if (!isTestPage && !isCompletionPage) {
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
                text = stringResource(R.string.app_picker_done),
                onClick = onFinishKeepRunning,
                modifier = modifier.ripDpiTestTag(RipDpiTestTags.OnboardingFinishKeepRunning),
            )
        }

        is OnboardingValidationState.Failed -> {
            // A permission failure names the permission to grant; the action asks for it again.
            val grantLabelRes = validationState.recoveryKind.grantActionLabelRes()
            RipDpiButton(
                text = stringResource(grantLabelRes ?: R.string.onboarding_test_retry),
                onClick = onRunValidation,
                leadingIcon = if (grantLabelRes != null) RipDpiIcons.Lock else null,
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

@StringRes
private fun OnboardingValidationRecoveryKind.grantActionLabelRes(): Int? =
    when (this) {
        OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION -> R.string.onboarding_test_grant_permission

        OnboardingValidationRecoveryKind.REQUEST_NOTIFICATIONS,
        OnboardingValidationRecoveryKind.REQUEST_NOTIFICATION_SETTINGS,
        -> R.string.permissions_notifications_title

        OnboardingValidationRecoveryKind.RETRY,
        OnboardingValidationRecoveryKind.SWITCH_MODE,
        -> null
    }

@Composable
private fun OnboardingInfoPageScene(
    pageModel: OnboardingPage.Informational,
    modifier: Modifier = Modifier,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val header = @Composable { headerModifier: Modifier ->
        OnboardingPageHeader(
            titleRes = pageModel.titleRes,
            subtitleRes = pageModel.descriptionRes,
            modifier = headerModifier,
        )
    }
    val grid = @Composable { gridModifier: Modifier ->
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
            modifier = gridModifier,
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val scrollModifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = introLayout.bodyHorizontalPadding)
                // A scroll child measures with unbounded height; the minimum lets the two landscape panes center
                // in the free space, and the content still scrolls from the top once it outgrows the page.
                .heightIn(min = maxHeight)
        if (maxWidth > maxHeight) {
            // Landscape leaves too little height to stack the header over the lists, so the
            // lists fell below the fold. Two panes keep both in view.
            Row(
                modifier = scrollModifier,
                horizontalArrangement = Arrangement.spacedBy(introLayout.bodyToContentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                header(Modifier.weight(1f))
                grid(Modifier.weight(1f))
            }
        } else {
            // Top-anchored like the setup steps: every title sits at one height under the pinned badge.
            Column(
                modifier = scrollModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(introLayout.bodyToContentGap),
            ) {
                header(Modifier)
                grid(Modifier)
            }
        }
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
        // Size each column to its own content (at most half the grid, so long labels wrap) and
        // center the pair. Equal-width columns left the narrower one with a wider right margin.
        BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val columnModifier =
                Modifier
                    .widthIn(max = (minOf(maxWidth, introLayout.guaranteeGridMaxWidth) - spacing.md) / 2)
                    .width(IntrinsicSize.Max)
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                OnboardingGuaranteeColumn(
                    headerRes = R.string.onboarding_guarantee_header_privacy,
                    labels = privacyLabels,
                    modifier = columnModifier,
                )
                OnboardingGuaranteeColumn(
                    headerRes = R.string.onboarding_guarantee_header_local,
                    labels = localLabels,
                    modifier = columnModifier,
                )
            }
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
            style = type.body,
            color = colors.mutedForeground,
            // Constrain to the remaining column width so long translated labels wrap, never clip.
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One coherent setup step: a header (title + optional subtitle) followed by the step content, laid
 * out as a single top-anchored scrollable group, so the title sits at the same height on every page
 * and overflow still scrolls at large font scales.
 */
@Composable
private fun OnboardingSetupPageScene(
    pageModel: OnboardingPage.Setup,
    uiState: OnboardingUiState,
    actions: OnboardingScreenActions,
    modifier: Modifier = Modifier,
) {
    val introLayout = rememberRipDpiIntroScaffoldMetrics()
    val useAccessibilityLayout = LocalDensity.current.fontScale >= AccessibilityOnboardingFontScale
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
            verticalArrangement = Arrangement.spacedBy(headerToContentGap),
        ) {
            val onTest = pageModel.kind == SetupPageKind.ConnectionTest
            // The tunnel of a passed test runs on the test step and on the completion step after it.
            val succeeded =
                (onTest || pageModel.kind == SetupPageKind.Completion) &&
                    uiState.validationState is OnboardingValidationState.Success
            OnboardingPageHeader(
                titleRes = if (succeeded) R.string.vpn_connected else pageModel.titleRes,
                subtitleRes =
                    pageModel.descriptionRes.takeIf {
                        !onTest || uiState.validationState == OnboardingValidationState.Idle
                    },
            )

            when (pageModel.kind) {
                SetupPageKind.Welcome -> {
                    OnboardingLanguageAction()
                }

                SetupPageKind.DnsSelection -> {
                    OnboardingDnsSelectionContent(
                        selectedDnsProviderId = uiState.selectedDnsProviderId,
                        onDnsSelected = actions.onDnsSelected,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SetupPageKind.BackgroundGuidance -> {
                    OnboardingBackgroundGuidanceContent(
                        mode = uiState.selectedMode,
                        onOpenBatterySettings = actions.onOpenBatterySettings,
                        onOpenVpnSettings = actions.onOpenVpnSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SetupPageKind.Completion -> {
                    OnboardingCompletionContent(uiState = uiState, modifier = Modifier.fillMaxWidth())
                }

                SetupPageKind.PersonaSelection -> {
                    OnboardingPersonaSelectionContent(
                        selectedPersona = uiState.selectedPersona,
                        onPersonaSelected = actions.onPersonaSelected,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SetupPageKind.ModeSelection -> {
                    OnboardingModeSelectionContent(
                        selectedMode = uiState.selectedMode,
                        onModeSelected = actions.onModeSelected,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SetupPageKind.ConnectionTest -> {
                    OnboardingModeValidationContent(
                        uiState = uiState,
                        onAcceptSuggestedMode = actions.onAcceptSuggestedMode,
                        onChangeDns = actions.onChangeDns,
                        // Finish without testing shows the completion step, which completes onboarding.
                        onFinish = actions.onShowCompletion,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Header shared by every onboarding page: title and optional subtitle. The step icon is the pinned badge. */
@Composable
private fun OnboardingPageHeader(
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
