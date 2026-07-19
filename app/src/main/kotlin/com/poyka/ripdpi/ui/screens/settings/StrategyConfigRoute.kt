package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.activities.StrategyConfigApplyResult
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.lua.LuaAssetManager
import com.poyka.ripdpi.services.NativeStrategyConfigRuntime
import com.poyka.ripdpi.services.StrategyConfigRuntime
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.security.SecureWindowEffect
import com.poyka.ripdpi.ui.state.SettingsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StrategyConfigRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    runtimeFactory: () -> StrategyConfigRuntime = { NativeStrategyConfigRuntime() },
    applySavedConfig: () -> StrategyConfigApplyResult = { StrategyConfigApplyResult.NextSession },
) {
    val context = LocalContext.current
    val editorViewModel: StrategyConfigEditorViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val runtime = remember(runtimeFactory) { runCatching { runtimeFactory() }.getOrNull() }
    val coroutineScope = rememberCoroutineScope()
    val editorSession = editorViewModel.sessionOrInitial(uiState.desync.chainDsl)
    var banner by remember { mutableStateOf<StrategyConfigBanner?>(null) }
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    val requestBack = editorViewModel::requestExit

    SecureWindowEffect()
    BackHandler(onBack = requestBack)
    StrategyConfigHydrationEffect(editorViewModel, uiState.desync.chainDsl)
    StrategyConfigExitEffect(
        decision = editorViewModel.exitDecision,
        onConsumed = editorViewModel::consumeExitDecision,
        onDirty = { showUnsavedChangesDialog = true },
        onBack = onBack,
    )

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            handleStrategyConfigImport(
                context = context,
                uri = uri,
                onImported = editorViewModel::importConfig,
                onBanner = { banner = it },
            )
        }

    StrategyConfigExitDialog(
        visible = showUnsavedChangesDialog,
        onKeepEditing = { showUnsavedChangesDialog = false },
        onDiscard = {
            showUnsavedChangesDialog = false
            coroutineScope.discardStrategyConfigDraft(
                editorViewModel = editorViewModel,
                onBack = onBack,
                onFailure = { showUnsavedChangesDialog = true },
            )
        },
    )

    StrategyConfigRouteScreen(
        context = context,
        editorViewModel = editorViewModel,
        editorSession = editorSession,
        settingsViewModel = viewModel,
        uiState = uiState,
        runtime = runtime,
        coroutineScope = coroutineScope,
        banner = banner,
        onBanner = { banner = it },
        onImport = { importLauncher.launch(StrategyConfigDocumentMimeTypes) },
        onBack = onBack,
        modifier = modifier,
        applySavedConfig = applySavedConfig,
    )
}

@Composable
private fun StrategyConfigRouteScreen(
    context: Context,
    editorViewModel: StrategyConfigEditorViewModel,
    editorSession: StrategyConfigEditorSession,
    settingsViewModel: SettingsViewModel,
    uiState: SettingsUiState,
    runtime: StrategyConfigRuntime?,
    coroutineScope: CoroutineScope,
    banner: StrategyConfigBanner?,
    onBanner: (StrategyConfigBanner?) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    applySavedConfig: () -> StrategyConfigApplyResult,
    modifier: Modifier,
) {
    StrategyConfigScreen(
        state =
            editorSession.toRouteScreenState(
                context = context,
                banner = banner,
                isHydrating = editorViewModel.isHydrating,
                hasHydrationError = editorViewModel.hasHydrationError,
            ),
        onBack = editorViewModel::requestExit,
        onSourceChanged = { source ->
            editorViewModel.selectSource(source, uiState.desync.chainDsl)
            onBanner(null)
        },
        onConfigTextChanged = { value ->
            editorViewModel.update { copy(configText = value.boundedUtf8(StrategyConfigMaxImportBytes)) }
        },
        onLuaPathChanged = { value -> editorViewModel.update { copy(luaPath = value) } },
        onLuaFunctionChanged = { value -> editorViewModel.update { copy(luaFunction = value) } },
        onImport = onImport,
        onExport = { editorViewModel.exportCurrentConfig(context, editorSession) },
        onSave = save@{
            val request = editorViewModel.beginSave() ?: return@save
            coroutineScope.launch {
                onBanner(
                    runStrategyConfigRouteSave(context, editorViewModel, request) {
                        saveStrategyConfigFromRoute(
                            context = context,
                            source = request.draft.source,
                            configText = request.draft.configText,
                            luaPath = request.draft.luaPath,
                            luaFunction = request.draft.luaFunction,
                            runtime = runtime,
                            viewModel = settingsViewModel,
                            applySavedConfig = applySavedConfig,
                            uiState = uiState,
                        )
                    },
                )
            }
        },
        onReload = { onBanner(reloadLuaConfig(context, runtime)) },
        onValidateLua = { onBanner(editorViewModel.validateCurrentLua(context, runtime, editorSession)) },
        onRetryRecovery = editorViewModel::retryHydration,
        onDiscardRecovery = {
            coroutineScope.discardStrategyConfigDraft(
                editorViewModel = editorViewModel,
                onBack = onBack,
                onFailure = {
                    onBanner(
                        StrategyConfigBanner(
                            title = context.getString(R.string.strategy_config_reload_failed_title),
                            message = strategyConfigInternalErrorMessage(context),
                            tone = WarningBannerTone.Error,
                        ),
                    )
                },
            )
        },
        modifier = modifier,
    )
}

private fun CoroutineScope.discardStrategyConfigDraft(
    editorViewModel: StrategyConfigEditorViewModel,
    onBack: () -> Unit,
    onFailure: () -> Unit,
) {
    launch {
        when (val failure = runCatching { editorViewModel.discard() }.exceptionOrNull()) {
            null -> onBack()
            is CancellationException -> throw failure
            else -> onFailure()
        }
    }
}

internal suspend fun runStrategyConfigRouteSave(
    context: Context,
    editorViewModel: StrategyConfigEditorViewModel,
    request: StrategyConfigSaveRequest,
    save: suspend () -> StrategyConfigBanner,
): StrategyConfigBanner =
    runCatching { editorViewModel.runSave(request, save) }
        .getOrElse { error ->
            if (error is CancellationException) throw error
            StrategyConfigBanner(
                title = context.getString(R.string.strategy_config_reload_failed_title),
                message = strategyConfigInternalErrorMessage(context),
                tone = WarningBannerTone.Error,
            )
        }

private fun StrategyConfigEditorSession.toRouteScreenState(
    context: Context,
    banner: StrategyConfigBanner?,
    isHydrating: Boolean,
    hasHydrationError: Boolean,
): StrategyConfigScreenState =
    toScreenState(
        activePath = activePathLabel(context, draft.source, draft.luaPath),
        banner = banner,
        isHydrating = isHydrating,
        hasHydrationError = hasHydrationError,
    )

private fun StrategyConfigEditorViewModel.sessionOrInitial(configText: String): StrategyConfigEditorSession =
    session ?: StrategyConfigEditorSession.initial(configText.boundedUtf8(StrategyConfigMaxImportBytes))

private fun StrategyConfigEditorViewModel.currentDraft(fallback: StrategyConfigEditorSession): StrategyConfigDraft =
    (session ?: fallback).draft

private fun StrategyConfigEditorViewModel.exportCurrentConfig(
    context: Context,
    fallback: StrategyConfigEditorSession,
) {
    shareStrategyConfig(context, currentDraft(fallback).configText)
}

private fun StrategyConfigEditorViewModel.validateCurrentLua(
    context: Context,
    runtime: StrategyConfigRuntime?,
    fallback: StrategyConfigEditorSession,
): StrategyConfigBanner = validateLuaScript(context, runtime, currentDraft(fallback).luaPath)

private val StrategyConfigDocumentMimeTypes =
    arrayOf(
        "text/*",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
        "application/octet-stream",
    )

private const val BytesPerKib = 1024

private fun handleStrategyConfigImport(
    context: Context,
    uri: android.net.Uri?,
    onImported: (String) -> Unit,
    onBanner: (StrategyConfigBanner) -> Unit,
) {
    uri?.let { selectedUri ->
        readStrategyConfigText(context, selectedUri)
            .onSuccess { imported ->
                onImported(imported)
                onBanner(
                    StrategyConfigBanner(
                        title = context.getString(R.string.strategy_config_imported_title),
                        message = context.getString(R.string.strategy_config_imported_body),
                        tone = WarningBannerTone.Info,
                    ),
                )
            }.onFailure { error ->
                onBanner(
                    StrategyConfigBanner(
                        title = context.getString(R.string.strategy_config_import_failed_title),
                        message = importErrorMessage(context, error),
                        tone = WarningBannerTone.Error,
                    ),
                )
            }
    }
}

private suspend fun saveStrategyConfigFromRoute(
    context: Context,
    source: StrategyConfigSource,
    configText: String,
    luaPath: String,
    luaFunction: String,
    runtime: StrategyConfigRuntime?,
    viewModel: SettingsViewModel,
    applySavedConfig: () -> StrategyConfigApplyResult,
    uiState: SettingsUiState,
): StrategyConfigBanner =
    saveStrategyConfig(
        context = context,
        source = source,
        configText = configText,
        luaPath = luaPath,
        luaFunction = luaFunction,
        runtime = runtime,
        saveChain = { value ->
            val parsed = parseStrategyChainDsl(value).getOrThrow()
            viewModel.updateSettingAndAwait("chainDsl", value) {
                setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
            }
        },
        saveRawStrategyConfig = { value ->
            viewModel.updateSettingAndAwait("strategyChainYaml", value) {
                if (value.isBlank()) {
                    clearStrategyChainYaml()
                } else {
                    setStrategyChainYaml(value)
                }
            }
        },
        applySavedConfig = applySavedConfig,
        uiState = uiState,
    )

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

private suspend fun saveStrategyConfig(
    context: Context,
    source: StrategyConfigSource,
    configText: String,
    luaPath: String,
    luaFunction: String,
    runtime: StrategyConfigRuntime?,
    saveChain: suspend (String) -> Unit,
    saveRawStrategyConfig: suspend (String) -> Unit,
    applySavedConfig: () -> StrategyConfigApplyResult,
    uiState: SettingsUiState,
): StrategyConfigBanner =
    when (source) {
        StrategyConfigSource.BuiltIn -> {
            val validation = validateStrategyConfigText(configText, uiState)
            if (validation.isSuccess) {
                saveAndApplyStrategyConfig(context, applySavedConfig) { saveChain(configText) }
            } else {
                StrategyConfigBanner(
                    title = context.getString(R.string.strategy_config_invalid_title),
                    message =
                        context.getString(R.string.config_error_invalid_chain),
                    tone = WarningBannerTone.Error,
                )
            }
        }

        StrategyConfigSource.CustomYaml -> {
            val validationError =
                if (runtime == null) {
                    context.getString(R.string.strategy_config_native_unavailable)
                } else {
                    runtime.validateStrategyConfigText(configText)
                }
            if (validationError == null) {
                saveAndApplyStrategyConfig(context, applySavedConfig) { saveRawStrategyConfig(configText) }
            } else {
                StrategyConfigBanner(
                    title = context.getString(R.string.strategy_config_invalid_title),
                    message = validationError,
                    tone = WarningBannerTone.Error,
                )
            }
        }

        StrategyConfigSource.LuaScript -> {
            saveLuaStrategyConfig(
                context = context,
                runtime = runtime,
                luaPath = luaPath,
                luaFunction = luaFunction,
                saveRawStrategyConfig = saveRawStrategyConfig,
                applySavedConfig = applySavedConfig,
            )
        }
    }

private suspend fun saveAndApplyStrategyConfig(
    context: Context,
    applySavedConfig: () -> StrategyConfigApplyResult,
    saveConfig: suspend () -> Unit,
): StrategyConfigBanner =
    runCatching {
        saveConfig()
        applySavedConfig()
    }.fold(
        onSuccess = { result -> savedStrategyConfigBanner(context, result) },
        onFailure = { error ->
            if (error is CancellationException) {
                throw error
            }
            StrategyConfigBanner(
                title = context.getString(R.string.strategy_config_reload_failed_title),
                message = strategyConfigInternalErrorMessage(context),
                tone = WarningBannerTone.Error,
            )
        },
    )

private fun savedStrategyConfigBanner(
    context: Context,
    result: StrategyConfigApplyResult,
): StrategyConfigBanner =
    StrategyConfigBanner(
        title = context.getString(R.string.strategy_config_saved_title),
        message =
            when (result) {
                StrategyConfigApplyResult.NextSession -> {
                    context.getString(R.string.strategy_config_saved_body)
                }

                StrategyConfigApplyResult.RestartingActiveService -> {
                    context.getString(R.string.strategy_config_saved_restarting_body)
                }

                StrategyConfigApplyResult.RestartAlreadyPending -> {
                    context.getString(R.string.strategy_config_saved_restart_pending_body)
                }
            },
        tone = WarningBannerTone.Info,
        saved = true,
    )

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

private suspend fun saveLuaStrategyConfig(
    context: Context,
    runtime: StrategyConfigRuntime?,
    luaPath: String,
    luaFunction: String,
    saveRawStrategyConfig: suspend (String) -> Unit,
    applySavedConfig: () -> StrategyConfigApplyResult,
): StrategyConfigBanner {
    val path = luaPath.trim()
    val function = luaFunction.trim()
    val yaml = luaStrategyConfigYaml(function = function, scriptPath = path)
    // The absolute <filesDir>/lua jail base passed into loadLuaScript; the
    // first load seeds the native jail from it (no trust-on-first-use).
    val baseDir =
        withContext(Dispatchers.IO) {
            runCatching { LuaAssetManager.ensureExtracted(context).toAbsolutePath().toString() }
        }
    return luaInputValidationBanner(context, path, function)
        ?: luaRuntimeValidationBanner(context, runtime, baseDir, path, function)
        ?: saveAndApplyStrategyConfig(context, applySavedConfig) { saveRawStrategyConfig(yaml) }
}

private fun luaInputValidationBanner(
    context: Context,
    path: String,
    function: String,
): StrategyConfigBanner? =
    when {
        path.isBlank() -> luaPathRequiredBanner(context)
        function.isBlank() -> luaFunctionRequiredBanner(context)
        else -> null
    }

private fun luaRuntimeValidationBanner(
    context: Context,
    runtime: StrategyConfigRuntime?,
    baseDir: Result<String>,
    path: String,
    function: String,
): StrategyConfigBanner? {
    val error =
        if (runtime == null) {
            context.getString(R.string.strategy_config_native_unavailable)
        } else {
            baseDir.fold(
                onSuccess = { dir -> runtime.loadLuaScript(dir, path) },
                onFailure = { strategyConfigInternalErrorMessage(context) },
            )
        }
    return when {
        error != null -> {
            StrategyConfigBanner(
                title = context.getString(R.string.strategy_config_import_failed_title),
                message = error,
                tone = WarningBannerTone.Error,
            )
        }

        runtime?.listLuaStrategies()?.none { it == function } != false -> {
            StrategyConfigBanner(
                title = context.getString(R.string.strategy_config_invalid_title),
                message = context.getString(R.string.strategy_config_lua_function_missing, function),
                tone = WarningBannerTone.Error,
            )
        }

        else -> {
            null
        }
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

private fun luaFunctionRequiredBanner(context: Context): StrategyConfigBanner =
    StrategyConfigBanner(
        title = context.getString(R.string.strategy_config_lua_function_required_title),
        message = context.getString(R.string.strategy_config_lua_function_required_body),
        tone = WarningBannerTone.Warning,
    )

internal fun luaStrategyConfigYaml(
    function: String,
    scriptPath: String,
): String =
    """
    version: 1
    strategies:
      - id: "${yamlQuote("lua:$function")}"
        steps:
          - type: lua
            function: "${yamlQuote(function)}"
            script_paths:
              - "${yamlQuote(scriptPath)}"
    """.trimIndent()

private fun yamlQuote(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

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
                StrategyConfigMaxImportBytes / BytesPerKib,
            )
        }

        StrategyConfigImportException.EmptyFile -> {
            context.getString(R.string.strategy_config_import_empty)
        }

        StrategyConfigImportException.InvalidUtf8,
        StrategyConfigImportException.UnreadableFile,
        -> {
            context.getString(R.string.strategy_config_import_unreadable)
        }

        else -> {
            context.getString(R.string.strategy_config_import_unreadable)
        }
    }

private fun strategyConfigInternalErrorMessage(context: Context): String =
    context.getString(R.string.update_error_unknown)
