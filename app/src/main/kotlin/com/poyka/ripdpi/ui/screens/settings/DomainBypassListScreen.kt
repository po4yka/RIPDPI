package com.poyka.ripdpi.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.rules.DomainBypassList
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Immutable render state for [DomainBypassListScreen]. */
internal data class DomainBypassListScreenState(
    val text: String,
    val errors: List<DomainBypassList.BypassListError>,
    val cleanCount: Int,
    val hasRule: Boolean,
)

@Composable
fun DomainBypassListRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DomainBypassListViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboardManager = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var compiled by remember { mutableStateOf(DomainBypassList.compile("")) }
    LaunchedEffect(uiState.text) {
        if (uiState.text.isNotEmpty()) delay(DomainCompileDebounceMillis)
        compiled = withContext(Dispatchers.Default) { DomainBypassList.compile(uiState.text) }
    }
    val moveDialogName = stringResource(R.string.domain_bypass_moved_rule_name)
    val ripdpiDomainsLabel = stringResource(R.string.clipboard_label_ripdpi_domains)

    DomainBypassListScreen(
        state =
            DomainBypassListScreenState(
                text = uiState.text,
                errors = compiled.errors,
                cleanCount = compiled.cleanLines.size,
                hasRule = uiState.hasRule,
            ),
        onBack = onBack,
        onTextChanged = viewModel::updateDraft,
        onSave = {
            viewModel.save { savedCount ->
                val message =
                    if (savedCount == 0) {
                        resources.getString(R.string.domain_bypass_cleared)
                    } else {
                        resources.getString(R.string.domain_bypass_saved, savedCount)
                    }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        },
        onPasteFromClipboard = {
            val pasted =
                clipboardManager
                    ?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
            if (pasted.isNullOrBlank()) {
                Toast.makeText(context, R.string.domain_bypass_clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.updateDraft(if (uiState.text.isBlank()) pasted else "${uiState.text}\n$pasted")
            }
        },
        onCopyToClipboard = {
            clipboardManager?.setPrimaryClip(
                ClipData.newPlainText(ripdpiDomainsLabel, uiState.text),
            )
            Toast.makeText(context, R.string.domain_bypass_copied, Toast.LENGTH_SHORT).show()
        },
        onMoveToRuleEditor = {
            viewModel.moveToRuleEditor(moveDialogName) { moved ->
                if (moved) {
                    Toast.makeText(context, R.string.domain_bypass_moved, Toast.LENGTH_SHORT).show()
                    viewModel.clearDraft()
                    onBack()
                }
            }
        },
        modifier = modifier,
    )
}

internal fun boundedDomainBypassDraft(text: String): String =
    text
        .lineSequence()
        .take(MaxDomainBypassLines)
        .joinToString("\n")
        .boundedUtf8(MaxDomainBypassBytes)

private const val MaxDomainBypassBytes = 64 * 1024
private const val MaxDomainBypassLines = 2_048
private const val DomainCompileDebounceMillis = 150L

@Composable
internal fun DomainBypassListScreen(
    state: DomainBypassListScreenState,
    onBack: () -> Unit,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onPasteFromClipboard: () -> Unit,
    onCopyToClipboard: () -> Unit,
    onMoveToRuleEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.DomainBypassList))
                .fillMaxSize(),
        title = stringResource(R.string.title_domain_bypass_list),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item(key = "domain_bypass_intro") {
            WarningBanner(
                title = stringResource(R.string.domain_bypass_intro_title),
                message = stringResource(R.string.domain_bypass_intro_body),
                tone = WarningBannerTone.Info,
            )
        }
        if (state.errors.isNotEmpty()) {
            item(key = "domain_bypass_errors") {
                val resources = LocalResources.current
                val errorMessage =
                    state.errors.joinToString("\n") { error ->
                        resources.getString(R.string.domain_bypass_error_line, error.lineNumber, error.message)
                    }
                WarningBanner(
                    title = stringResource(R.string.domain_bypass_errors_title, state.errors.size),
                    message = errorMessage,
                    tone = WarningBannerTone.Error,
                )
            }
        }
        item(key = "domain_bypass_editor") {
            DomainBypassEditorCard(
                state = state,
                onTextChanged = onTextChanged,
                onSave = onSave,
                onPasteFromClipboard = onPasteFromClipboard,
                onCopyToClipboard = onCopyToClipboard,
                onMoveToRuleEditor = onMoveToRuleEditor,
            )
        }
    }
}

@Composable
private fun DomainBypassEditorCard(
    state: DomainBypassListScreenState,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onPasteFromClipboard: () -> Unit,
    onCopyToClipboard: () -> Unit,
    onMoveToRuleEditor: () -> Unit,
) {
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            Text(
                text = stringResource(R.string.domain_bypass_editor_label),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            RipDpiConfigTextField(
                value = state.text,
                onValueChange = onTextChanged,
                decoration =
                    RipDpiTextFieldDecoration(
                        placeholder = stringResource(R.string.domain_bypass_placeholder),
                        helperText =
                            stringResource(R.string.domain_bypass_clean_count, state.cleanCount),
                        testTag = RipDpiTestTags.DomainBypassEditor,
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Default,
                            ),
                    ),
                multiline = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                RipDpiButton(
                    text = stringResource(R.string.config_save),
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    density = RipDpiControlDensity.Compact,
                    leadingIcon = RipDpiIcons.Check,
                )
                RipDpiButton(
                    text = stringResource(R.string.domain_bypass_paste),
                    onClick = onPasteFromClipboard,
                    modifier = Modifier.weight(1f),
                    variant = RipDpiButtonVariant.Outline,
                    density = RipDpiControlDensity.Compact,
                    leadingIcon = RipDpiIcons.Copy,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                RipDpiButton(
                    text = stringResource(R.string.domain_bypass_copy),
                    onClick = onCopyToClipboard,
                    modifier = Modifier.weight(1f),
                    variant = RipDpiButtonVariant.Outline,
                    density = RipDpiControlDensity.Compact,
                    leadingIcon = RipDpiIcons.Share,
                )
                RipDpiButton(
                    text = stringResource(R.string.domain_bypass_move_to_editor),
                    onClick = onMoveToRuleEditor,
                    modifier = Modifier.weight(1f),
                    variant = RipDpiButtonVariant.Ghost,
                    density = RipDpiControlDensity.Compact,
                    enabled = state.hasRule,
                    leadingIcon = RipDpiIcons.Advanced,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun previewDomainBypassListScreen() {
    RipDpiTheme(themePreference = "light") {
        DomainBypassListScreen(
            state =
                DomainBypassListScreenState(
                    text = "bank.ru\n.gov.ru\ndomain_regex:^(bad",
                    errors =
                        listOf(
                            DomainBypassList.BypassListError(
                                lineNumber = 3,
                                raw = "domain_regex:^(bad",
                                message = "Invalid regex: unclosed group",
                            ),
                        ),
                    cleanCount = 2,
                    hasRule = true,
                ),
            onBack = {},
            onTextChanged = {},
            onSave = {},
            onPasteFromClipboard = {},
            onCopyToClipboard = {},
            onMoveToRuleEditor = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun previewDomainBypassListScreenDark() {
    RipDpiTheme(themePreference = "dark") {
        DomainBypassListScreen(
            state =
                DomainBypassListScreenState(
                    text = "bank.ru\n.gov.ru",
                    errors = emptyList(),
                    cleanCount = 2,
                    hasRule = true,
                ),
            onBack = {},
            onTextChanged = {},
            onSave = {},
            onPasteFromClipboard = {},
            onCopyToClipboard = {},
            onMoveToRuleEditor = {},
        )
    }
}
