package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistDetectionResult
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistDetector
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistProbeTrace
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistUrlGroup
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistVerdict
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class DiagnosticsCidrWhitelistController(
    private val scope: CoroutineScope,
    private val detector: CidrWhitelistDetector,
) {
    private val _tool = MutableStateFlow(DiagnosticsCidrWhitelistToolUiModel())
    val tool: StateFlow<DiagnosticsCidrWhitelistToolUiModel> = _tool.asStateFlow()

    fun run() {
        if (_tool.value.state == DiagnosticsCidrWhitelistState.Running) {
            return
        }
        _tool.value =
            DiagnosticsCidrWhitelistToolUiModel(
                state = DiagnosticsCidrWhitelistState.Running,
                summary = "Checking whitelisted and regular URL groups...",
            )
        scope.launch {
            runCatching {
                detector.detect()
            }.onSuccess { result ->
                _tool.value = result.toUiModel()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _tool.value =
                    DiagnosticsCidrWhitelistToolUiModel(
                        state = DiagnosticsCidrWhitelistState.Failed,
                        summary = "CIDR whitelist detection failed.",
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
            }
        }
    }
}

internal fun CidrWhitelistDetectionResult.toUiModel(): DiagnosticsCidrWhitelistToolUiModel {
    val whitelisted = traces.filter { trace -> trace.group == CidrWhitelistUrlGroup.WHITELISTED }
    val regular = traces.filter { trace -> trace.group == CidrWhitelistUrlGroup.REGULAR }
    val whitelistedOk = whitelisted.count { trace -> trace.ok }
    val regularOk = regular.count { trace -> trace.ok }
    return DiagnosticsCidrWhitelistToolUiModel(
        state = DiagnosticsCidrWhitelistState.Complete,
        summary = verdict.summaryText(),
        metrics =
            listOf(
                DiagnosticsMetricUiModel(
                    "whitelisted",
                    "$whitelistedOk/${whitelisted.size}",
                    reachabilityTone(whitelistedOk),
                ),
                DiagnosticsMetricUiModel(
                    "regular",
                    "$regularOk/${regular.size}",
                    reachabilityTone(regularOk),
                ),
                DiagnosticsMetricUiModel("verdict", verdict.displayLabel(), verdict.tone()),
            ).toPersistentList(),
        rows = traces.map { trace -> trace.toUiModel() }.toPersistentList(),
    )
}

private fun CidrWhitelistProbeTrace.toUiModel(): DiagnosticsCidrWhitelistTraceUiModel =
    DiagnosticsCidrWhitelistTraceUiModel(
        group = group.displayLabel(),
        url = url,
        status = statusCode?.toString() ?: if (ok) "ok" else "failed",
        latency = "$latencyMs ms",
        error = error,
        tone = if (ok) DiagnosticsTone.Positive else DiagnosticsTone.Warning,
    )

private fun CidrWhitelistUrlGroup.displayLabel(): String =
    when (this) {
        CidrWhitelistUrlGroup.WHITELISTED -> "whitelisted"
        CidrWhitelistUrlGroup.REGULAR -> "regular"
    }

private fun CidrWhitelistVerdict.displayLabel(): String =
    when (this) {
        CidrWhitelistVerdict.OK -> "ok"
        CidrWhitelistVerdict.CIDR_WHITELIST_DETECTED -> "cidr whitelist"
        CidrWhitelistVerdict.NO_INTERNET -> "no internet"
    }

private fun CidrWhitelistVerdict.summaryText(): String =
    when (this) {
        CidrWhitelistVerdict.OK -> "Regular URLs are reachable; CIDR whitelist pattern not detected."
        CidrWhitelistVerdict.CIDR_WHITELIST_DETECTED -> "CIDR whitelist pattern detected: only control URLs responded."
        CidrWhitelistVerdict.NO_INTERNET -> "Neither URL group responded; connection may be offline."
    }

private fun CidrWhitelistVerdict.tone(): DiagnosticsTone =
    when (this) {
        CidrWhitelistVerdict.OK -> DiagnosticsTone.Positive
        CidrWhitelistVerdict.CIDR_WHITELIST_DETECTED -> DiagnosticsTone.Warning
        CidrWhitelistVerdict.NO_INTERNET -> DiagnosticsTone.Negative
    }

private fun reachabilityTone(okCount: Int): DiagnosticsTone =
    if (okCount > 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }
