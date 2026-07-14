package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistDetectionResult
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistDetector
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistProbeTrace
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistUrlGroup
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistVerdict
import com.poyka.ripdpi.platform.StringResolver
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
    private val stringResolver: StringResolver,
) {
    private val _tool = MutableStateFlow(initialDiagnosticsCidrWhitelistUiModel(stringResolver))
    val tool: StateFlow<DiagnosticsCidrWhitelistToolUiModel> = _tool.asStateFlow()

    fun run() {
        if (_tool.value.state == DiagnosticsCidrWhitelistState.Running) {
            return
        }
        _tool.value =
            DiagnosticsCidrWhitelistToolUiModel(
                state = DiagnosticsCidrWhitelistState.Running,
                summary = stringResolver.getString(R.string.diagnostics_cidr_whitelist_running),
            )
        scope.launch {
            runCatching {
                detector.detect()
            }.onSuccess { result ->
                _tool.value = result.toUiModel(stringResolver)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _tool.value =
                    DiagnosticsCidrWhitelistToolUiModel(
                        state = DiagnosticsCidrWhitelistState.Failed,
                        summary = stringResolver.getString(R.string.diagnostics_cidr_whitelist_failed),
                        errorMessage = error.message ?: error.javaClass.simpleName,
                    )
            }
        }
    }
}

internal fun CidrWhitelistDetectionResult.toUiModel(
    stringResolver: StringResolver,
): DiagnosticsCidrWhitelistToolUiModel {
    val whitelisted = traces.filter { trace -> trace.group == CidrWhitelistUrlGroup.WHITELISTED }
    val regular = traces.filter { trace -> trace.group == CidrWhitelistUrlGroup.REGULAR }
    val whitelistedOk = whitelisted.count { trace -> trace.ok }
    val regularOk = regular.count { trace -> trace.ok }
    return DiagnosticsCidrWhitelistToolUiModel(
        state = DiagnosticsCidrWhitelistState.Complete,
        summary = verdict.summaryText(stringResolver),
        metrics =
            listOf(
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_cidr_whitelist_metric_whitelisted),
                    "$whitelistedOk/${whitelisted.size}",
                    reachabilityTone(whitelistedOk),
                ),
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_cidr_whitelist_metric_regular),
                    "$regularOk/${regular.size}",
                    reachabilityTone(regularOk),
                ),
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_cidr_whitelist_metric_verdict),
                    verdict.displayLabel(stringResolver),
                    verdict.tone(),
                ),
            ).toPersistentList(),
        rows = traces.map { trace -> trace.toUiModel(stringResolver) }.toPersistentList(),
    )
}

private fun CidrWhitelistProbeTrace.toUiModel(stringResolver: StringResolver): DiagnosticsCidrWhitelistTraceUiModel =
    DiagnosticsCidrWhitelistTraceUiModel(
        group = group.displayLabel(stringResolver),
        url = url,
        status =
            statusCode?.toString() ?: stringResolver.getString(
                if (ok) {
                    R.string.diagnostics_cidr_whitelist_status_ok
                } else {
                    R.string.diagnostics_cidr_whitelist_status_failed
                },
            ),
        latency = "$latencyMs ms",
        error = error,
        tone = if (ok) DiagnosticsTone.Positive else DiagnosticsTone.Warning,
    )

private fun CidrWhitelistUrlGroup.displayLabel(stringResolver: StringResolver): String =
    when (this) {
        CidrWhitelistUrlGroup.WHITELISTED -> {
            stringResolver.getString(R.string.diagnostics_cidr_whitelist_metric_whitelisted)
        }

        CidrWhitelistUrlGroup.REGULAR -> {
            stringResolver.getString(R.string.diagnostics_cidr_whitelist_metric_regular)
        }
    }

private fun CidrWhitelistVerdict.displayLabel(stringResolver: StringResolver): String =
    when (this) {
        CidrWhitelistVerdict.OK -> {
            stringResolver.getString(R.string.diagnostics_cidr_whitelist_status_ok)
        }

        CidrWhitelistVerdict.CIDR_WHITELIST_DETECTED -> {
            stringResolver.getString(R.string.diagnostics_cidr_whitelist_verdict_detected_label)
        }

        CidrWhitelistVerdict.NO_INTERNET -> {
            stringResolver.getString(R.string.diagnostics_cidr_whitelist_verdict_no_internet_label)
        }
    }

private fun CidrWhitelistVerdict.summaryText(stringResolver: StringResolver): String =
    when (this) {
        CidrWhitelistVerdict.OK -> {
            stringResolver.getString(R.string.diagnostics_cidr_whitelist_summary_ok)
        }

        CidrWhitelistVerdict.CIDR_WHITELIST_DETECTED -> {
            stringResolver.getString(R.string.diagnostics_cidr_whitelist_summary_detected)
        }

        CidrWhitelistVerdict.NO_INTERNET -> {
            stringResolver.getString(R.string.diagnostics_cidr_whitelist_summary_no_internet)
        }
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
