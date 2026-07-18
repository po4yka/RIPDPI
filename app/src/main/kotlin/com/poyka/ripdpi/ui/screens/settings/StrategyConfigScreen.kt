package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf

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

@Composable
private fun StrategyConfigSourceCard(
    state: StrategyConfigScreenState,
    onSourceChanged: (StrategyConfigSource) -> Unit,
) {
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
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            Text(
                text = stringResource(R.string.strategy_config_editor_label),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            RipDpiConfigTextField(
                value = state.configText,
                onValueChange = onConfigTextChanged,
                decoration =
                    RipDpiTextFieldDecoration(
                        placeholder = stringResource(R.string.config_placeholder_chain_dsl),
                        testTag = RipDpiTestTags.StrategyConfigEditor,
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                    ),
                multiline = true,
            )
            StrategyConfigActionRows(
                primaryLabel = stringResource(R.string.config_save),
                primaryIcon = RipDpiIcons.Check,
                onPrimary = onSave,
                primaryLoading = state.isSaving,
                secondaryLabel = stringResource(R.string.strategy_config_reload_action),
                secondaryIcon = RipDpiIcons.NetworkCheck,
                onSecondary = onReload,
            )
            StrategyConfigActionRows(
                primaryLabel = stringResource(R.string.config_relay_import),
                primaryIcon = RipDpiIcons.Public,
                onPrimary = onImport,
                secondaryLabel = stringResource(R.string.strategy_config_export_action),
                secondaryIcon = RipDpiIcons.Share,
                onSecondary = onExport,
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
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiConfigTextField(
                value = state.luaPath,
                onValueChange = onLuaPathChanged,
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.strategy_config_lua_path_label),
                        placeholder = stringResource(R.string.strategy_config_lua_path_placeholder),
                        testTag = RipDpiTestTags.StrategyConfigLuaPath,
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    ),
            )
            RipDpiConfigTextField(
                value = state.luaFunction,
                onValueChange = onLuaFunctionChanged,
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.strategy_config_lua_function_label),
                        placeholder = stringResource(R.string.strategy_config_lua_function_placeholder),
                        testTag = RipDpiTestTags.StrategyConfigLuaFunction,
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                    ),
            )
            StrategyConfigActionRows(
                primaryLabel = stringResource(R.string.strategy_config_validate_action),
                primaryIcon = RipDpiIcons.Check,
                onPrimary = onValidateLua,
                secondaryLabel = stringResource(R.string.strategy_config_load_action),
                secondaryIcon = RipDpiIcons.NetworkCheck,
                onSecondary = onSave,
                secondaryLoading = state.isSaving,
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
                )
            }
        }
    }
}

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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        RipDpiButton(
            text = primaryLabel,
            onClick = onPrimary,
            modifier = Modifier.weight(1f),
            density = RipDpiControlDensity.Compact,
            leadingIcon = primaryIcon,
            loading = primaryLoading,
        )
        RipDpiButton(
            text = secondaryLabel,
            onClick = onSecondary,
            modifier = Modifier.weight(1f),
            variant = RipDpiButtonVariant.Outline,
            density = RipDpiControlDensity.Compact,
            leadingIcon = secondaryIcon,
            loading = secondaryLoading,
        )
    }
}

@Composable
private fun rememberStrategyConfigSourceOptions() =
    persistentListOf(
        RipDpiDropdownOption(StrategyConfigSource.BuiltIn, stringResource(R.string.strategy_config_source_builtin)),
        RipDpiDropdownOption(StrategyConfigSource.CustomYaml, stringResource(R.string.strategy_config_source_yaml)),
        RipDpiDropdownOption(StrategyConfigSource.LuaScript, stringResource(R.string.strategy_config_source_lua)),
    )
