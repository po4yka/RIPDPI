package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf

private const val RecoveryActionsStackFontScale = 1.3f
private val RecoveryActionsSideBySideMinWidth = 320.dp
private val StrategyConfigActionsSideBySideMinWidth = 320.dp
private val LuaPathInputTransformation =
    InputTransformation.byValue { _, proposed -> proposed.toString().boundedUtf8(StrategyConfigMaxLuaPathBytes) }
private val LuaFunctionInputTransformation =
    InputTransformation.byValue { _, proposed -> proposed.toString().boundedUtf8(StrategyConfigMaxLuaFunctionBytes) }

internal enum class StrategyConfigSource {
    BuiltIn,
    CustomYaml,
    LuaScript,
}

internal data class StrategyConfigBanner(
    val title: String,
    val message: String,
    val tone: WarningBannerTone,
    val saved: Boolean = false,
)

internal data class StrategyConfigScreenState(
    val source: StrategyConfigSource,
    val configText: String,
    val luaPath: String,
    val luaFunction: String,
    val activePath: String,
    val banner: StrategyConfigBanner?,
    val isSaving: Boolean = false,
    val isHydrating: Boolean = false,
    val hasHydrationError: Boolean = false,
    val isFinalizingSave: Boolean = false,
    val replacementRevision: Long = 0,
)

@Composable
internal fun StrategyConfigScreen(
    state: StrategyConfigScreenState,
    onBack: () -> Unit,
    onSourceChanged: (StrategyConfigSource) -> Unit,
    onConfigTextChanged: (String) -> Unit,
    onLuaPathChanged: (String) -> Unit,
    onLuaFunctionChanged: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onValidateLua: () -> Unit,
    onRetryRecovery: () -> Unit = {},
    onDiscardRecovery: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.StrategyConfig))
                .fillMaxSize(),
        title = stringResource(R.string.title_strategy_config),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        item(key = "strategy_config_banner") {
            state.banner?.let { banner ->
                WarningBanner(
                    title = banner.title,
                    message = banner.message,
                    tone = banner.tone,
                )
            }
        }
        when {
            state.hasHydrationError -> {
                item(key = "strategy_config_recovery") {
                    StrategyConfigRecoveryCard(
                        onRetry = onRetryRecovery,
                        onDiscard = onDiscardRecovery,
                    )
                }
            }

            state.isHydrating -> {
                item(key = "strategy_config_restoring") {
                    StrategyConfigRestoringCard()
                }
            }

            else -> {
                item(key = "strategy_config_source") {
                    StrategyConfigSourceCard(
                        state = state,
                        onSourceChanged = onSourceChanged,
                    )
                }
                item(key = "strategy_config_editor") {
                    if (state.source == StrategyConfigSource.LuaScript) {
                        LuaStrategyConfigCard(
                            state = state,
                            onLuaPathChanged = onLuaPathChanged,
                            onLuaFunctionChanged = onLuaFunctionChanged,
                            onValidateLua = onValidateLua,
                            onReload = onReload,
                            onSave = onSave,
                        )
                    } else {
                        TextStrategyConfigCard(
                            state = state,
                            onConfigTextChanged = onConfigTextChanged,
                            onImport = onImport,
                            onExport = onExport,
                            onSave = onSave,
                            onReload = onReload,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyConfigRestoringCard() {
    RipDpiCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RipDpiSpinner()
            Text(
                text = stringResource(R.string.strategy_config_restoring_draft),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.foreground,
            )
        }
    }
}

@Composable
private fun StrategyConfigRecoveryCard(
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            Text(
                text = stringResource(R.string.strategy_config_restore_failed_title),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = stringResource(R.string.strategy_config_restore_failed_body),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stackActions =
                    LocalDensity.current.fontScale >= RecoveryActionsStackFontScale ||
                        maxWidth < RecoveryActionsSideBySideMinWidth
                if (stackActions) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
                    ) {
                        StrategyConfigRecoveryButtons(
                            onRetry = onRetry,
                            onDiscard = onDiscard,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
                    ) {
                        StrategyConfigRecoveryButtons(
                            onRetry = onRetry,
                            onDiscard = onDiscard,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyConfigRecoveryButtons(
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier,
) {
    RipDpiButton(
        text = stringResource(R.string.unsaved_changes_discard),
        onClick = onDiscard,
        modifier = modifier,
        variant = RipDpiButtonVariant.Outline,
    )
    RipDpiButton(
        text = stringResource(R.string.strategy_config_restore_retry),
        onClick = onRetry,
        modifier = modifier,
    )
}

@Composable
private fun StrategyConfigSourceCard(
    state: StrategyConfigScreenState,
    onSourceChanged: (StrategyConfigSource) -> Unit,
) {
    val editingEnabled = !state.isHydrating && !state.isFinalizingSave
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            SettingsRow(
                title = stringResource(R.string.strategy_config_active_path_label),
                value = state.activePath,
                monospaceValue = true,
                showDivider = true,
            )
            RipDpiDropdown(
                options = rememberStrategyConfigSourceOptions(),
                selectedValue = state.source,
                onValueSelected = onSourceChanged,
                enabled = editingEnabled,
                testTag = RipDpiTestTags.StrategyConfigSource,
            )
        }
    }
}

@Composable
private fun TextStrategyConfigCard(
    state: StrategyConfigScreenState,
    onConfigTextChanged: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
) {
    val editingEnabled = !state.isHydrating && !state.isFinalizingSave
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            Text(
                text = stringResource(R.string.strategy_config_editor_label),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            RipDpiConfigTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = state.configText,
                        onValueChange = onConfigTextChanged,
                        replacementKey = state.replacementRevision,
                    ),
                decoration =
                    RipDpiTextFieldDecoration(
                        placeholder = stringResource(R.string.config_placeholder_chain_dsl),
                        testTag = RipDpiTestTags.StrategyConfigEditor,
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        enabled = editingEnabled,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                    ),
                inputTransformation =
                    InputTransformation.byValue { _, proposed ->
                        proposed.toString().boundedUtf8(StrategyConfigMaxImportBytes)
                    },
                lineLimits = TextFieldLineLimits.MultiLine(),
            )
            StrategyConfigActionRows(
                primaryLabel = stringResource(R.string.config_save),
                primaryIcon = RipDpiIcons.Check,
                onPrimary = onSave,
                primaryLoading = state.isSaving,
                secondaryLabel = stringResource(R.string.strategy_config_reload_action),
                secondaryIcon = RipDpiIcons.NetworkCheck,
                onSecondary = onReload,
                enabled = editingEnabled,
            )
            StrategyConfigActionRows(
                primaryLabel = stringResource(R.string.config_relay_import),
                primaryIcon = RipDpiIcons.Public,
                onPrimary = onImport,
                secondaryLabel = stringResource(R.string.strategy_config_export_action),
                secondaryIcon = RipDpiIcons.Share,
                onSecondary = onExport,
                enabled = editingEnabled,
            )
        }
    }
}

@Composable
private fun LuaStrategyConfigCard(
    state: StrategyConfigScreenState,
    onLuaPathChanged: (String) -> Unit,
    onLuaFunctionChanged: (String) -> Unit,
    onValidateLua: () -> Unit,
    onReload: () -> Unit,
    onSave: () -> Unit,
) {
    val editingEnabled = !state.isHydrating && !state.isFinalizingSave
    val (luaPathState, luaFunctionState) =
        rememberLuaStrategyTextFields(state, onLuaPathChanged, onLuaFunctionChanged)
    val configTextStyle =
        RipDpiThemeTokens.type.monoConfig.copy(
            textAlign = TextAlign.Start,
            textDirection = TextDirection.Ltr,
        )
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiConfigTextField(
                state = luaPathState,
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.strategy_config_lua_path_label),
                        placeholder = stringResource(R.string.strategy_config_lua_path_placeholder),
                        testTag = RipDpiTestTags.StrategyConfigLuaPath,
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        enabled = editingEnabled,
                        textStyle = configTextStyle,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    ),
                inputTransformation = LuaPathInputTransformation,
            )
            RipDpiConfigTextField(
                state = luaFunctionState,
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.strategy_config_lua_function_label),
                        placeholder = stringResource(R.string.strategy_config_lua_function_placeholder),
                        testTag = RipDpiTestTags.StrategyConfigLuaFunction,
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        enabled = editingEnabled,
                        textStyle = configTextStyle,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                    ),
                inputTransformation = LuaFunctionInputTransformation,
            )
            StrategyConfigActionRows(
                primaryLabel = stringResource(R.string.strategy_config_validate_action),
                primaryIcon = RipDpiIcons.Check,
                onPrimary = onValidateLua,
                secondaryLabel = stringResource(R.string.strategy_config_load_action),
                secondaryIcon = RipDpiIcons.NetworkCheck,
                onSecondary = onSave,
                secondaryLoading = state.isSaving,
                enabled = editingEnabled,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                RipDpiButton(
                    text = stringResource(R.string.strategy_config_reload_action),
                    onClick = onReload,
                    variant = RipDpiButtonVariant.Outline,
                    density = RipDpiControlDensity.Compact,
                    leadingIcon = RipDpiIcons.NetworkCheck,
                    enabled = editingEnabled,
                )
            }
        }
    }
}

@Composable
private fun rememberLuaStrategyTextFields(
    state: StrategyConfigScreenState,
    onLuaPathChanged: (String) -> Unit,
    onLuaFunctionChanged: (String) -> Unit,
): Pair<TextFieldState, TextFieldState> =
    rememberRipDpiTextFieldState(
        state.luaPath,
        onLuaPathChanged,
        replacementKey = state.replacementRevision,
    ) to
        rememberRipDpiTextFieldState(
            state.luaFunction,
            onLuaFunctionChanged,
            replacementKey = state.replacementRevision,
        )

@Composable
private fun StrategyConfigActionRows(
    primaryLabel: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    secondaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onSecondary: () -> Unit,
    primaryLoading: Boolean = false,
    secondaryLoading: Boolean = false,
    enabled: Boolean = true,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackActions =
            maxWidth < StrategyConfigActionsSideBySideMinWidth * LocalDensity.current.fontScale
        if (stackActions) {
            Column(
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                StrategyConfigActionButtons(
                    primaryLabel = primaryLabel,
                    primaryIcon = primaryIcon,
                    onPrimary = onPrimary,
                    secondaryLabel = secondaryLabel,
                    secondaryIcon = secondaryIcon,
                    onSecondary = onSecondary,
                    primaryLoading = primaryLoading,
                    secondaryLoading = secondaryLoading,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                StrategyConfigActionButtons(
                    primaryLabel = primaryLabel,
                    primaryIcon = primaryIcon,
                    onPrimary = onPrimary,
                    secondaryLabel = secondaryLabel,
                    secondaryIcon = secondaryIcon,
                    onSecondary = onSecondary,
                    primaryLoading = primaryLoading,
                    secondaryLoading = secondaryLoading,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StrategyConfigActionButtons(
    primaryLabel: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    secondaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onSecondary: () -> Unit,
    primaryLoading: Boolean,
    secondaryLoading: Boolean,
    enabled: Boolean,
    modifier: Modifier,
) {
    RipDpiButton(
        text = primaryLabel,
        onClick = onPrimary,
        modifier = modifier,
        density = RipDpiControlDensity.Compact,
        leadingIcon = primaryIcon,
        loading = primaryLoading,
        enabled = enabled,
    )
    RipDpiButton(
        text = secondaryLabel,
        onClick = onSecondary,
        modifier = modifier,
        variant = RipDpiButtonVariant.Outline,
        density = RipDpiControlDensity.Compact,
        leadingIcon = secondaryIcon,
        loading = secondaryLoading,
        enabled = enabled,
    )
}

@Composable
private fun rememberStrategyConfigSourceOptions() =
    persistentListOf(
        RipDpiDropdownOption(StrategyConfigSource.BuiltIn, stringResource(R.string.strategy_config_source_builtin)),
        RipDpiDropdownOption(StrategyConfigSource.CustomYaml, stringResource(R.string.strategy_config_source_yaml)),
        RipDpiDropdownOption(StrategyConfigSource.LuaScript, stringResource(R.string.strategy_config_source_lua)),
    )
