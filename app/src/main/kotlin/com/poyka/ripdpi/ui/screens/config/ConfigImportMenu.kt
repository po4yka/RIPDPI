package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.proxyimport.ProxyImportRequest
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.screens.proxyimport.ClipboardImportViewModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Profile top-bar overflow menu carrying the explicit "Import from clipboard"
 * action.
 *
 * Privacy contract: the clipboard is read **only** when the user taps the menu item — the
 * tap is what calls [ClipboardImportViewModel.onImportFromClipboard]. There is no watcher,
 * no auto-paste detection, and nothing reads the clipboard while this menu merely sits in
 * the top bar. On Android 12+ the system clipboard-access toast is expected and is not
 * suppressed.
 *
 * A recognised proxy URI routes to the profile-import confirmation destination via
 * [onProfileImport]; unknown content or an empty clipboard surface a typed error here.
 */
@Composable
fun ConfigImportMenu(
    onProfileImport: (ProxyImportRequest.Profile) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ClipboardImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        RipDpiIconButton(
            icon = RipDpiIcons.Overflow,
            contentDescription = stringResource(R.string.import_clipboard_action),
            onClick = { expanded = true },
            style = RipDpiIconButtonStyle.Ghost,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigOverflowMenuButton),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigImportClipboardMenuItem),
                text = {
                    val surfaceStyle =
                        RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.inputs.dropdownMenu)
                    Text(
                        text = stringResource(R.string.import_clipboard_action),
                        style = RipDpiThemeTokens.type.body,
                        color = surfaceStyle.content,
                    )
                },
                onClick = {
                    expanded = false
                    // The clipboard read happens here — and only here — on the user's tap.
                    viewModel.onImportFromClipboard()
                },
            )
        }
    }

    val navigateTo = uiState.navigateToConfirm
    if (navigateTo is ProxyImportRequest.Profile) {
        LaunchedEffect(navigateTo) {
            viewModel.consumeNavigation()
            onProfileImport(navigateTo)
        }
    }

    ClipboardImportErrorBanner(
        unknownContentScheme = uiState.unknownContentScheme,
        clipboardEmpty = uiState.clipboardEmpty,
        onDismiss = viewModel::dismissError,
    )
}

/**
 * Renders the typed clipboard-import error (unknown content with its scheme, or an empty
 * clipboard) as a dismissible dialog. A dialog rather than an inline banner so the error
 * is not constrained by wherever the menu host composable sits (e.g. a top-bar RowScope).
 * The raw clipboard payload is never shown — at most the scheme prefix.
 */
@Composable
private fun ClipboardImportErrorBanner(
    unknownContentScheme: String?,
    clipboardEmpty: Boolean,
    onDismiss: () -> Unit,
) {
    val title: String
    val message: String
    when {
        unknownContentScheme != null -> {
            title = stringResource(R.string.import_clipboard_unknown_title)
            message =
                if (unknownContentScheme.isEmpty()) {
                    stringResource(R.string.import_clipboard_unknown_no_scheme)
                } else {
                    stringResource(R.string.import_clipboard_unknown_with_scheme, unknownContentScheme)
                }
        }

        clipboardEmpty -> {
            title = stringResource(R.string.import_clipboard_empty_title)
            message = stringResource(R.string.import_clipboard_empty_body)
        }

        else -> {
            return
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        com.poyka.ripdpi.ui.components.cards.RipDpiCard {
            com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader(
                title = title,
                supporting = message,
            )
            com.poyka.ripdpi.ui.components.buttons.RipDpiButton(
                text = stringResource(R.string.navigation_back),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
