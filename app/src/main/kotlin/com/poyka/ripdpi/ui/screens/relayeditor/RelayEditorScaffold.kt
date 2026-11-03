package com.poyka.ripdpi.ui.screens.relayeditor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiTopBarAction
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.profiles.RelayEditorHint
import com.poyka.ripdpi.ui.screens.profiles.RelayPasswordField
import com.poyka.ripdpi.ui.screens.profiles.RelayProfileNotFoundCard
import com.poyka.ripdpi.ui.screens.profiles.ScrollToTopOnFailedSave
import com.poyka.ripdpi.ui.screens.profiles.relayPortRangeHelper
import com.poyka.ripdpi.ui.security.SecureWindowEffect
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons

/** Save-button and body state shared by every relay profile editor. */
internal data class RelayEditorStatus(
    val complete: Boolean,
    val saving: Boolean,
    val loading: Boolean = false,
    val notFound: Boolean = false,
    @StringRes val errorMessage: Int? = null,
)

/**
 * The shell of a relay profile editor: secure window, top bar with the Save action, error card, the reason Save is
 * disabled, and the not-found card. [banner] sits above the error card; [content] holds the per-protocol sections.
 */
@Composable
internal fun RelayEditorScaffold(
    kind: String,
    route: Route,
    title: String,
    saveLabel: String,
    status: RelayEditorStatus,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    SecureWindowEffect()
    val scrollState = rememberScrollState()
    ScrollToTopOnFailedSave(scrollState, status.saving, status.errorMessage != null)
    RipDpiContentScreenScaffold(
        scrollState = scrollState,
        title = title,
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        actions = {
            RipDpiTopBarAction(
                text = saveLabel,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.relayEditorSave(kind)),
                onClick = onSave,
                enabled = status.complete && !status.saving && !status.loading,
            )
        },
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(route)),
    ) {
        if (status.notFound) {
            RelayProfileNotFoundCard()
            return@RipDpiContentScreenScaffold
        }
        banner()
        status.errorMessage?.let { errorRes ->
            RipDpiCard { RipDpiPanelHeader(title = stringResource(errorRes)) }
        }
        // Above the form: Save is disabled until the form is complete, and the reason must show without scrolling.
        RelayEditorHint(stringResource(R.string.relay_editor_save_incomplete_hint).takeUnless { status.complete })
        content()
    }
}

/**
 * Text and password fields of one protocol's editor, keyed by its field enum [F]. [rawText] and [hasError] read the
 * protocol's editor state; every field uses the protocol's own [invalidRes] message and the tag
 * `relayEditorField(kind, field.name)`.
 */
internal class RelayFieldBinding<F : Enum<F>>(
    private val kind: String,
    @StringRes private val invalidRes: Int,
    private val rawText: (F) -> String,
    private val hasError: (F) -> Boolean,
    private val onChanged: (F, String) -> Unit,
) {
    @Composable
    fun Text(
        field: F,
        @StringRes labelRes: Int,
        keyboardType: KeyboardType = KeyboardType.Text,
        placeholder: String? = null,
        helperText: String? = null,
        readOnly: Boolean = false,
    ) {
        RipDpiTextField(
            value = rawText(field),
            onValueChange = { onChanged(field, it) },
            modifier = Modifier.fillMaxWidth(),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(labelRes),
                    placeholder = placeholder,
                    helperText = helperText,
                    testTag = RipDpiTestTags.relayEditorField(kind, field.name),
                    errorText = if (hasError(field)) stringResource(invalidRes) else null,
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    readOnly = readOnly,
                ),
        )
    }

    @Composable
    fun Password(
        field: F,
        @StringRes labelRes: Int,
    ) {
        RelayPasswordField(
            label = stringResource(labelRes),
            value = rawText(field),
            onValueChange = { onChanged(field, it) },
            testTag = RipDpiTestTags.relayEditorField(kind, field.name),
            errorText = stringResource(invalidRes).takeIf { hasError(field) },
        )
    }

    /** The name, server and port card, with the protocol's own [name], [server] and [port] fields. */
    @Composable
    fun EndpointCard(
        name: F,
        server: F,
        port: F,
        @StringRes titleRes: Int,
        @StringRes nameRes: Int,
        @StringRes serverRes: Int,
        @StringRes portRes: Int,
        nameReadOnly: Boolean = false,
    ) {
        RipDpiCard {
            RipDpiPanelHeader(title = stringResource(titleRes))
            Text(name, nameRes, readOnly = nameReadOnly)
            Text(server, serverRes)
            Text(port, portRes, keyboardType = KeyboardType.Number, helperText = relayPortRangeHelper())
        }
    }
}
