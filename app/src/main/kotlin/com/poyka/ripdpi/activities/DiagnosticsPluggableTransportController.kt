package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpich.MeekReachabilityProbe
import com.poyka.ripdpi.diagnostics.dpich.Obfs4ReachabilityProbe
import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportReachabilityProbe
import com.poyka.ripdpi.diagnostics.dpich.loadMeekFrontUrls
import com.poyka.ripdpi.diagnostics.dpich.loadObfs4BridgeEndpoints
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DiagnosticsPluggableTransportController(
    private val scope: CoroutineScope,
    appSettingsRepository: AppSettingsRepository,
    private val assetLoader: DpiAssetLoader,
    private val stringResolver: StringResolver,
) {
    private val _tool = MutableStateFlow(DiagnosticsPluggableTransportToolUiModel())
    val tool: StateFlow<DiagnosticsPluggableTransportToolUiModel> = _tool.asStateFlow()

    init {
        scope.launch {
            appSettingsRepository.settings.collect { settings ->
                val current = _tool.value
                _tool.value =
                    current.copy(
                        privacyModeEnabled = settings.detectionCheckPrivacyModeEnabled,
                        state =
                            when {
                                settings.detectionCheckPrivacyModeEnabled -> {
                                    DiagnosticsPluggableTransportState.Disabled
                                }

                                current.state == DiagnosticsPluggableTransportState.Disabled -> {
                                    DiagnosticsPluggableTransportState.Idle
                                }

                                else -> {
                                    current.state
                                }
                            },
                        summary =
                            if (settings.detectionCheckPrivacyModeEnabled) {
                                stringResolver.getString(R.string.diagnostics_pt_privacy_disabled)
                            } else {
                                current.summary
                            },
                    )
            }
        }
    }

    fun run() {
        val current = _tool.value
        if (current.state == DiagnosticsPluggableTransportState.Running) {
            return
        }
        if (current.privacyModeEnabled) {
            _tool.value =
                current.copy(
                    state = DiagnosticsPluggableTransportState.Disabled,
                    summary = stringResolver.getString(R.string.diagnostics_pt_privacy_disabled),
                    errorMessage = null,
                )
            return
        }
        _tool.value =
            DiagnosticsPluggableTransportToolUiModel(
                state = DiagnosticsPluggableTransportState.Running,
                summary = stringResolver.getString(R.string.diagnostics_pt_running),
                privacyModeEnabled = false,
            )
        scope.launch {
            runCatching {
                loadProbe().run()
            }.onSuccess { result ->
                _tool.value = result.toUiModel(privacyModeEnabled = false, stringResolver = stringResolver)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _tool.value =
                    DiagnosticsPluggableTransportToolUiModel(
                        state = DiagnosticsPluggableTransportState.Failed,
                        summary = stringResolver.getString(R.string.diagnostics_pt_failed),
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
            }
        }
    }

    private suspend fun loadProbe(): PluggableTransportReachabilityProbe =
        withContext(Dispatchers.IO) {
            PluggableTransportReachabilityProbe(
                obfs4Probe = Obfs4ReachabilityProbe(bridges = assetLoader.loadObfs4BridgeEndpoints()),
                meekProbe = MeekReachabilityProbe(fronts = assetLoader.loadMeekFrontUrls()),
            )
        }
}
