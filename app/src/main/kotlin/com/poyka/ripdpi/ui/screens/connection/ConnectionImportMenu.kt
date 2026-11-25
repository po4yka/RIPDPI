package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.window.Dialog
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.RipDpiContextMenu
import com.poyka.ripdpi.ui.components.feedback.RipDpiContextMenuAction
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
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
fun ConnectionImportMenu(
    unknownContentScheme: String?,
    clipboardEmpty: Boolean,
    onImportFromClipboard: () -> Unit,
    onDismissError: () -> Unit,
    onLinkEntered: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        RipDpiIconButton(
            icon = RipDpiIcons.Overflow,
            // The button opens the menu; the import action belongs to the menu item.
            contentDescription = stringResource(R.string.config_overflow_more_options),
            onClick = { expanded = true },
            style = RipDpiIconButtonStyle.Ghost,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ConnectionOverflowMenuButton),
        )
        RipDpiContextMenu(
            visible = expanded,
            actions =
                persistentListOf(
                    RipDpiContextMenuAction(
                        label = stringResource(R.string.import_clipboard_action),
                        icon = RipDpiIcons.Paste,
                        testTag = RipDpiTestTags.ConnectionImportClipboardMenuItem,
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
        onLinkEntered = onLinkEntered,
    )
}

/**
 * Renders the typed clipboard-import error as a dismissible dialog. An empty clipboard offers a field
 * to paste or type the link instead. A dialog rather than an inline banner so the error is not
 * constrained by wherever the menu host composable sits (e.g. a top-bar RowScope).
 * The raw clipboard payload is never shown — at most the scheme prefix.
 */
@Composable
internal fun ClipboardImportErrorBanner(
    unknownContentScheme: String?,
    clipboardEmpty: Boolean,
    onDismiss: () -> Unit,
    onLinkEntered: (String) -> Unit,
) {
    when {
        unknownContentScheme != null -> {
            val message =
                if (unknownContentScheme.isEmpty()) {
                    stringResource(R.string.import_clipboard_unknown_no_scheme)
                } else {
                    stringResource(R.string.import_clipboard_unknown_with_scheme, unknownContentScheme)
                }
            Dialog(onDismissRequest = onDismiss) {
                RipDpiCard {
                    RipDpiPanelHeader(
                        title = stringResource(R.string.import_clipboard_unknown_title),
                        supporting = message,
                    )
                    RipDpiButton(
                        text = stringResource(R.string.navigation_back),
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        clipboardEmpty -> {
            EnterLinkDialog(onDismiss = onDismiss, onLinkEntered = onLinkEntered)
        }
    }
}

@Composable
private fun EnterLinkDialog(
    onDismiss: () -> Unit,
    onLinkEntered: (String) -> Unit,
) {
    // Saved state could write the credential-bearing link to disk, so the field uses plain remember.
    var link by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        RipDpiCard {
            RipDpiPanelHeader(title = stringResource(R.string.import_clipboard_empty_title))
            RipDpiTextField(
                value = link,
                onValueChange = { link = it },
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.import_link_label),
                        testTag = RipDpiTestTags.ClipboardImportLink,
                    ),
                // A URI keyboard without autocorrect keeps the token-bearing link out of keyboard suggestions.
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    ),
            )
            RipDpiButton(
                text = stringResource(R.string.import_link_action),
                onClick = { onLinkEntered(link) },
                enabled = link.isNotBlank(),
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.ClipboardImportSubmit),
            )
            RipDpiButton(
                text = stringResource(R.string.navigation_back),
                onClick = onDismiss,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
