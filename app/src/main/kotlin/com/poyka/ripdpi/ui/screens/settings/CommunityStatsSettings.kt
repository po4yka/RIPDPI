package com.poyka.ripdpi.ui.screens.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** Navigation rows for the community statistics source; the URL is edited in a dialog, not inline. */
@Composable
internal fun CommunityStatsSettingsRows(
    communityApiUrl: String,
    communityApiUrlDraft: String,
    onCommunityApiUrlDraftChanged: (String) -> Unit,
    onCommunityApiUrlChanged: (String) -> Unit,
    onClearCommunityCache: () -> Unit,
) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.settings_community_source_title),
        subtitle = stringResource(R.string.settings_community_source_body),
        value =
            communityApiUrl.takeIf(String::isNotBlank)?.let { Uri.parse(it).host ?: it }
                ?: stringResource(R.string.settings_community_source_default),
        onClick = { showEditor = true },
        showDivider = true,
    )
    SettingsRow(
        title = stringResource(R.string.settings_clear_community_cache),
        subtitle = stringResource(R.string.settings_clear_community_cache_body),
        onClick = onClearCommunityCache,
        showDivider = true,
    )
    if (!showEditor) return
    val close = {
        onCommunityApiUrlDraftChanged(communityApiUrl)
        showEditor = false
    }
    val save = {
        onCommunityApiUrlChanged(communityApiUrlDraft)
        showEditor = false
    }
    // Save is the dialog's confirm action, so it is the only filled button and Cancel stays secondary.
    RipDpiDialog(
        onDismissRequest = close,
        title = stringResource(R.string.settings_community_source_title),
        visuals = RipDpiDialogVisuals(message = stringResource(R.string.settings_community_source_body)),
        dismissAction = RipDpiDialogAction(label = stringResource(R.string.config_cancel), onClick = close),
        confirmAction =
            RipDpiDialogAction(label = stringResource(R.string.settings_community_api_url_save), onClick = save),
    ) {
        CommunityApiUrlEditor(
            value = communityApiUrlDraft,
            onValueChange = onCommunityApiUrlDraftChanged,
            onSave = save,
            onReset = {
                onCommunityApiUrlDraftChanged("")
                onCommunityApiUrlChanged("")
                showEditor = false
            },
        )
    }
}

@Composable
internal fun CommunityApiUrlEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        RipDpiTextField(
            value = value,
            onValueChange = onValueChange,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.settings_community_api_url_label),
                    placeholder = "https://",
                    helperText = stringResource(R.string.settings_community_api_url_helper),
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    onKeyboardAction = KeyboardActionHandler { onSave() },
                ),
        )
        RipDpiButton(
            text = stringResource(R.string.settings_community_api_url_reset),
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            enabled = value.isNotBlank(),
            variant = RipDpiButtonVariant.Outline,
        )
    }
}
