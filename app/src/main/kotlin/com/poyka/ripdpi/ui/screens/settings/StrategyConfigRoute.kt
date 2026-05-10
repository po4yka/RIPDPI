package com.poyka.ripdpi.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.services.NativeStrategyConfigRuntime
import com.poyka.ripdpi.services.StrategyConfigRuntime
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.state.SettingsUiState

@Composable
fun StrategyConfigRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    runtimeFactory: () -> StrategyConfigRuntime = { NativeStrategyConfigRuntime() },
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val binder = remember(viewModel) { AdvancedSettingsBinder(viewModel::updateSetting) }
    val runtime = remember(runtimeFactory) { runCatching { runtimeFactory() }.getOrNull() }
    var source by rememberSaveable { mutableStateOf(StrategyConfigSource.BuiltIn) }
    var configText by rememberSaveable { mutableStateOf(uiState.desync.chainDsl) }
    var luaPath by rememberSaveable { mutableStateOf("") }
    var luaFunction by rememberSaveable { mutableStateOf("") }
    var banner by rememberSaveable { mutableStateOf<StrategyConfigBanner?>(null) }

    SecureStrategyConfigWindowEffect(context)
    LaunchedEffect(uiState.desync.chainDsl, source) {
        if (source == StrategyConfigSource.BuiltIn) {
            configText = uiState.desync.chainDsl
        }
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                readStrategyConfigText(context, uri)
                    .onSuccess { imported ->
                        configText = imported
                        source = StrategyConfigSource.CustomYaml
                        banner =
                            StrategyConfigBanner(
                                title = context.getString(R.string.strategy_config_imported_title),
                                message = context.getString(R.string.strategy_config_imported_body),
                                tone = WarningBannerTone.Info,
                            )
                    }.onFailure { error ->
                        banner =
                            StrategyConfigBanner(
                                title = context.getString(R.string.strategy_config_import_failed_title),
                                message = importErrorMessage(context, error),
                                tone = WarningBannerTone.Error,
                            )
                    }
            }
        }

    StrategyConfigScreen(
        state =
            StrategyConfigScreenState(
                source = source,
                configText = configText,
                luaPath = luaPath,
                luaFunction = luaFunction,
                activePath = activePathLabel(context, source, luaPath),
                banner = banner,
            ),
        onBack = onBack,
        onSourceChanged = {
            source = it
            banner = null
        },
        onConfigTextChanged = { configText = it },
        onLuaPathChanged = { luaPath = it },
        onLuaFunctionChanged = { luaFunction = it },
        onImport = { importLauncher.launch(StrategyConfigDocumentMimeTypes) },
        onExport = { shareStrategyConfig(context, configText) },
        onSave = {
            banner =
                saveStrategyConfig(
                    context = context,
                    source = source,
                    configText = configText,
                    luaPath = luaPath,
                    runtime = runtime,
                    saveChain = { value -> binder.onTextConfirmed(AdvancedTextSetting.ChainDsl, value, uiState) },
                    uiState = uiState,
                )
        },
        onReload = { banner = reloadLuaConfig(context, runtime) },
        onValidateLua = { banner = validateLuaScript(context, runtime, luaPath) },
        modifier = modifier,
    )
}

private val StrategyConfigDocumentMimeTypes =
    arrayOf(
        "text/*",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
        "application/octet-stream",
    )

@Composable
private fun SecureStrategyConfigWindowEffect(context: Context) {
    val window = (context as? Activity)?.window
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private fun activePathLabel(
    context: Context,
    source: StrategyConfigSource,
    luaPath: String,
): String =
    when (source) {
        StrategyConfigSource.BuiltIn -> {
            context.getString(R.string.strategy_config_path_datastore)
        }

        StrategyConfigSource.CustomYaml -> {
            context.getString(R.string.strategy_config_path_imported)
        }

        StrategyConfigSource.LuaScript -> {
            luaPath.ifBlank {
                context.getString(
                    R.string.strategy_config_path_not_selected,
                )
            }
        }
    }

private fun saveStrategyConfig(
    context: Context,
    source: StrategyConfigSource,
    configText: String,
    luaPath: String,
    runtime: StrategyConfigRuntime?,
    saveChain: (String) -> Unit,
    uiState: SettingsUiState,
): StrategyConfigBanner =
    when (source) {
        StrategyConfigSource.BuiltIn,
        StrategyConfigSource.CustomYaml,
        -> {
            val validation = validateStrategyConfigText(configText, uiState)
            if (validation.isSuccess) {
                saveChain(configText)
                val reloadError =
                    if (runtime == null) {
                        context.getString(R.string.strategy_config_native_unavailable)
                    } else {
                        runtime.reloadConfig()
                    }
                if (reloadError == null) {
                    StrategyConfigBanner(
                        title = context.getString(R.string.strategy_config_saved_title),
                        message = context.getString(R.string.strategy_config_saved_body),
                        tone = WarningBannerTone.Info,
                    )
                } else {
                    StrategyConfigBanner(
                        title = context.getString(R.string.strategy_config_reload_failed_title),
                        message = reloadError,
                        tone = WarningBannerTone.Error,
                    )
                }
            } else {
                StrategyConfigBanner(
                    title = context.getString(R.string.strategy_config_invalid_title),
                    message =
                        validation.exceptionOrNull()?.localizedMessage
                            ?: context.getString(R.string.config_error_invalid_chain),
                    tone = WarningBannerTone.Error,
                )
            }
        }

        StrategyConfigSource.LuaScript -> {
            loadLuaScript(context, runtime, luaPath)
        }
    }

private fun validateStrategyConfigText(
    configText: String,
    uiState: SettingsUiState,
): Result<Unit> =
    runCatching {
        val chain = parseStrategyChainDsl(configText).getOrThrow()
        validateStrategyChainUsage(
            tcpSteps = chain.tcpSteps,
            udpSteps = chain.udpSteps,
            mode = uiState.selectedMode,
            useCommandLineSettings = uiState.enableCmdSettings,
        )
    }

private fun validateLuaScript(
    context: Context,
    runtime: StrategyConfigRuntime?,
    luaPath: String,
): StrategyConfigBanner {
    if (luaPath.isBlank()) {
        return luaPathRequiredBanner(context)
    }
    val error =
        if (runtime == null) {
            context.getString(R.string.strategy_config_native_unavailable)
        } else {
            runtime.validateLuaScript(luaPath)
        }
    return if (error == null) {
        StrategyConfigBanner(
            title = context.getString(R.string.strategy_config_lua_valid_title),
            message = context.getString(R.string.strategy_config_lua_valid_body),
            tone = WarningBannerTone.Info,
        )
    } else {
        StrategyConfigBanner(
            title = context.getString(R.string.strategy_config_invalid_title),
            message = error,
            tone = WarningBannerTone.Error,
        )
    }
}

private fun loadLuaScript(
    context: Context,
    runtime: StrategyConfigRuntime?,
    luaPath: String,
): StrategyConfigBanner {
    if (luaPath.isBlank()) {
        return luaPathRequiredBanner(context)
    }
    val error =
        if (runtime == null) {
            context.getString(R.string.strategy_config_native_unavailable)
        } else {
            runtime.loadLuaScript(luaPath)
        }
    return if (error == null) {
        StrategyConfigBanner(
            title = context.getString(R.string.strategy_config_lua_loaded_title),
            message = context.getString(R.string.strategy_config_lua_loaded_body),
            tone = WarningBannerTone.Info,
        )
    } else {
        StrategyConfigBanner(
            title = context.getString(R.string.strategy_config_import_failed_title),
            message = error,
            tone = WarningBannerTone.Error,
        )
    }
}

private fun reloadLuaConfig(
    context: Context,
    runtime: StrategyConfigRuntime?,
): StrategyConfigBanner {
    val error =
        if (runtime == null) {
            context.getString(R.string.strategy_config_native_unavailable)
        } else {
            runtime.reloadConfig()
        }
    return if (error == null) {
        StrategyConfigBanner(
            title = context.getString(R.string.strategy_config_reloaded_title),
            message = context.getString(R.string.strategy_config_reloaded_body),
            tone = WarningBannerTone.Info,
        )
    } else {
        StrategyConfigBanner(
            title = context.getString(R.string.strategy_config_reload_failed_title),
            message = error,
            tone = WarningBannerTone.Error,
        )
    }
}

private fun luaPathRequiredBanner(context: Context): StrategyConfigBanner =
    StrategyConfigBanner(
        title = context.getString(R.string.strategy_config_lua_path_required_title),
        message = context.getString(R.string.strategy_config_lua_path_required_body),
        tone = WarningBannerTone.Warning,
    )

private fun shareStrategyConfig(
    context: Context,
    configText: String,
) {
    val intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, configText)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.title_strategy_config))
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.strategy_config_export_title)))
}

private fun importErrorMessage(
    context: Context,
    error: Throwable,
): String =
    when (error) {
        StrategyConfigImportException.FileTooLarge -> {
            context.getString(
                R.string.strategy_config_import_too_large,
                StrategyConfigMaxImportBytes / 1024,
            )
        }

        StrategyConfigImportException.EmptyFile -> {
            context.getString(R.string.strategy_config_import_empty)
        }

        else -> {
            error.localizedMessage ?: context.getString(R.string.strategy_config_import_unreadable)
        }
    }
