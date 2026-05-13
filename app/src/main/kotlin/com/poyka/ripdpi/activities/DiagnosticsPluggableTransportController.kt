package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpich.MeekReachabilityProbe
import com.poyka.ripdpi.diagnostics.dpich.Obfs4ReachabilityProbe
import com.poyka.ripdpi.diagnostics.dpich.PluggableTransportReachabilityProbe
import com.poyka.ripdpi.diagnostics.dpich.loadMeekFrontUrls
import com.poyka.ripdpi.diagnostics.dpich.loadObfs4BridgeEndpoints
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
                                PtPrivacyDisabledSummary
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
                    summary = PtPrivacyDisabledSummary,
                    errorMessage = null,
                )
            return
        }
        _tool.value =
            DiagnosticsPluggableTransportToolUiModel(
                state = DiagnosticsPluggableTransportState.Running,
                summary = "Checking PT endpoint reachability...",
                privacyModeEnabled = false,
            )
        scope.launch {
            runCatching {
                loadProbe().run()
            }.onSuccess { result ->
                _tool.value = result.toUiModel(privacyModeEnabled = false)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _tool.value =
                    DiagnosticsPluggableTransportToolUiModel(
                        state = DiagnosticsPluggableTransportState.Failed,
                        summary = "PT reachability check failed.",
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

    private companion object {
        private const val PtPrivacyDisabledSummary =
            "Privacy Mode is enabled. Disable Privacy Mode to run PT endpoint reachability checks."
    }
}
