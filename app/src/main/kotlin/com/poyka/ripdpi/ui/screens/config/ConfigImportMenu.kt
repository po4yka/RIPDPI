package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.components.feedback.RipDpiContextMenu
import com.poyka.ripdpi.ui.components.feedback.RipDpiContextMenuAction
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import kotlinx.collections.immutable.persistentListOf

/**
 * Profile top-bar overflow menu carrying the explicit "Import from clipboard"
 * action.
 *
 * Privacy contract: the clipboard is read **only** when the user taps the menu item — the
 * tap is what calls [onImportFromClipboard]. There is no watcher,
 * no auto-paste detection, and nothing reads the clipboard while this menu merely sits in
 * the top bar. On Android 12+ the system clipboard-access toast is expected and is not
 * suppressed.
 *
 * Navigation is owned by the route; this stateless menu only dispatches the explicit
 * import action and renders typed clipboard errors.
 */
@Composable
fun ConfigImportMenu(
    unknownContentScheme: String?,
    clipboardEmpty: Boolean,
    onImportFromClipboard: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        RipDpiIconButton(
            icon = RipDpiIcons.Overflow,
            contentDescription = stringResource(R.string.import_clipboard_action),
            onClick = { expanded = true },
            style = RipDpiIconButtonStyle.Ghost,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConfigOverflowMenuButton),
        )
        RipDpiContextMenu(
            visible = expanded,
            actions =
                persistentListOf(
                    RipDpiContextMenuAction(
                        label = stringResource(R.string.import_clipboard_action),
                        icon = RipDpiIcons.Copy,
                        testTag = RipDpiTestTags.ConfigImportClipboardMenuItem,
                        // The clipboard read happens here — and only here — on the user's tap.
                        onClick = onImportFromClipboard,
                    ),
                ),
            onDismiss = { expanded = false },
        )
    }

    ClipboardImportErrorBanner(
        unknownContentScheme = unknownContentScheme,
        clipboardEmpty = clipboardEmpty,
        onDismiss = onDismissError,
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
