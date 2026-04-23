package com.poyka.ripdpi.ui.screens.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.services.OwnedStackBrowserBackend
import com.poyka.ripdpi.services.OwnedStackExecutionTrace
import com.poyka.ripdpi.services.OwnedStackNativeFallbackReason
import com.poyka.ripdpi.services.SecureHttpEchMode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun OwnedStackBrowserRoute(
    initialUrl: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OwnedStackBrowserViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel, initialUrl) {
        viewModel.initialize(initialUrl)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    OwnedStackBrowserScreen(
        uiState = uiState,
        onBack = onBack,
        onUrlChanged = viewModel::updateUrl,
        onOpen = viewModel::reload,
        modifier = modifier,
    )
}

@Composable
internal fun OwnedStackBrowserScreen(
    uiState: OwnedStackBrowserUiState,
    onBack: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type

    RipDpiScreenScaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .ripDpiTestTag(RipDpiTestTags.screen(Route.OwnedStackBrowser())),
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.title_owned_stack_browser),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.navigation_back),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = layout.horizontalPadding, vertical = spacing.sm)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            WarningBanner(
                title = stringResource(R.string.owned_stack_browser_banner_title),
                message =
                    if (uiState.support.android17EchEligible) {
                        stringResource(R.string.owned_stack_browser_banner_android17)
                    } else {
                        stringResource(R.string.owned_stack_browser_banner_fallback)
                    },
                tone =
                    if (uiState.support.android17EchEligible) {
                        WarningBannerTone.Info
                    } else {
                        WarningBannerTone.Restricted
                    },
            )
            RipDpiCard(variant = RipDpiCardVariant.Outlined) {
                Text(
                    text = stringResource(R.string.owned_stack_browser_url_label),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                RipDpiTextField(
                    value = uiState.inputUrl,
                    onValueChange = onUrlChanged,
                    decoration =
                        RipDpiTextFieldDecoration(
                            placeholder = stringResource(R.string.owned_stack_browser_url_placeholder),
                        ),
                    behavior =
                        RipDpiTextFieldBehavior(
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go,
                                ),
                            singleLine = true,
                        ),
                )
                RipDpiButton(
                    text = stringResource(R.string.owned_stack_browser_open_action),
                    onClick = onOpen,
                    enabled = uiState.inputUrl.isNotBlank() && !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            uiState.errorMessage?.let { message ->
                WarningBanner(
                    title = stringResource(R.string.owned_stack_browser_error_title),
                    message = message,
                    tone = WarningBannerTone.Warning,
                )
            }
            if (uiState.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.page?.let { page ->
                RipDpiCard(variant = RipDpiCardVariant.Elevated) {
                    StatusIndicator(
                        label =
                            when (page.backend) {
                                OwnedStackBrowserBackend.HTTP_ENGINE -> {
                                    stringResource(R.string.owned_stack_browser_backend_http_engine)
                                }

                                OwnedStackBrowserBackend.NATIVE_OWNED_TLS -> {
                                    stringResource(R.string.owned_stack_browser_backend_native_fallback)
                                }
                            },
                        tone =
                            if (page.statusCode in 200..299) {
                                StatusIndicatorTone.Active
                            } else {
                                StatusIndicatorTone.Warning
                            },
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.owned_stack_browser_status_value,
                                page.statusCode,
                                page.finalUrl,
                            ),
                        style = type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                    if (page.tlsProfileId != null) {
                        Text(
                            text = stringResource(R.string.owned_stack_browser_tls_profile_value, page.tlsProfileId),
                            style = type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                    }
                    ownedStackTraceSummary(page.executionTrace)?.let { summary ->
                        Text(
                            text = context.getString(summary),
                            style = type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                    }
                }
                RipDpiCard(variant = RipDpiCardVariant.Outlined) {
                    SelectionContainer {
                        Text(
                            text =
                                page.bodyText.ifBlank {
                                    stringResource(R.string.owned_stack_browser_empty_body)
                                },
                            style = type.body,
                            color = colors.foreground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ownedStackTraceSummary(trace: OwnedStackExecutionTrace): Int? =
    when {
        trace.nativeFallbackReason == OwnedStackNativeFallbackReason.ECH_CONFIRMATION_MISSING -> {
            R.string.owned_stack_browser_trace_native_ech_missing
        }

        trace.nativeFallbackReason == OwnedStackNativeFallbackReason.PLATFORM_FAILURE -> {
            R.string.owned_stack_browser_trace_native_platform_failure
        }

        trace.nativeFallbackReason == OwnedStackNativeFallbackReason.PLATFORM_UNAVAILABLE -> {
            R.string.owned_stack_browser_trace_native_platform_unavailable
        }

        trace.h2RetryTriggered -> {
            R.string.owned_stack_browser_trace_h2_retry
        }

        trace.effectiveEchMode == SecureHttpEchMode.REQUIRE_CONFIRMED -> {
            R.string.owned_stack_browser_trace_confirmed_ech
        }

        trace.platformAttempted -> {
            R.string.owned_stack_browser_trace_opportunistic_platform
        }

        else -> {
            null
        }
    }
