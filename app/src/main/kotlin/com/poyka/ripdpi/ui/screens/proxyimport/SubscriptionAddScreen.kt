// RDS deviation: subscription-add — no spec card exists; built from existing RDS
// components (scaffold, card, panel header, text field, button) and tokens only.
package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.R
import com.poyka.ripdpi.proxyimport.PendingSubscriptionImportRequest
import com.poyka.ripdpi.proxyimport.SubscriptionLinkClassifier
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.security.SecureWindowEffect
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Holds the typed link across configuration changes. The link can carry an access token,
 * so it lives in memory only and never in saved state.
 */
@HiltViewModel
class SubscriptionAddViewModel
    @Inject
    constructor() : ViewModel() {
        var url by mutableStateOf("")
    }

/**
 * In-app entry for a subscription: the user types or pastes a subscription link, and
 * [onContinue] hands the classified request to the existing confirmation screen.
 */
@Composable
internal fun SubscriptionAddRoute(
    onBack: () -> Unit,
    onContinue: (PendingSubscriptionImportRequest) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionAddViewModel = hiltViewModel(),
) {
    SubscriptionAddScreen(
        url = viewModel.url,
        onUrlChange = { viewModel.url = it },
        onBack = onBack,
        onContinue = { SubscriptionLinkClassifier.classify(viewModel.url)?.let(onContinue) },
        modifier = modifier,
    )
}

@Composable
internal fun SubscriptionAddScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureWindowEffect()
    val valid = remember(url) { SubscriptionLinkClassifier.classify(url) != null }
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.import_subscription_confirm_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.SubscriptionAdd)),
    ) {
        RipDpiCard {
            RipDpiPanelHeader(
                title = stringResource(R.string.subscription_add_url_label),
                supporting = stringResource(R.string.subscription_add_body),
            )
            RipDpiTextField(
                value = url,
                onValueChange = onUrlChange,
                decoration =
                    RipDpiTextFieldDecoration(
                        placeholder = "https://",
                        errorText =
                            if (url.isNotBlank() && !valid) {
                                stringResource(R.string.subscription_add_url_invalid)
                            } else {
                                null
                            },
                        testTag = RipDpiTestTags.SubscriptionAddUrl,
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions =
                            KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    ),
            )
        }
        RipDpiButton(
            text = stringResource(R.string.subscription_add_continue),
            onClick = onContinue,
            enabled = valid,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.SubscriptionAddContinue),
        )
    }
}
