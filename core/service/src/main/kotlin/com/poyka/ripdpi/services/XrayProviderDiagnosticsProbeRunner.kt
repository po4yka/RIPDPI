package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.XrayNativeBridge
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayProfileRedactor
import com.poyka.ripdpi.data.xray.XrayProviderProbeKind
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.data.xray.XrayProviderProbeResult
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot

/**
 * User-triggered provider-path diagnostics runner (decision 5).
 *
 * Mirrors the existing user-triggered DPI-tools pattern (the app's
 * `DiagnosticsDpiToolsController.run*()`): it executes ONLY when the user asks
 * and ONLY against the ACTIVE running provider. It is NOT a reachability scanner
 * and performs no auto-scanning.
 *
 * ### Which probes run in-process
 * - [XrayProviderProbeKind.Version] — pure `version()` read; safe everywhere.
 * - [XrayProviderProbeKind.ListenerReadiness] — loopback listener readiness via
 *   `listenerReady()`; safe everywhere.
 * - [XrayProviderProbeKind.WrapperPing] — the bridge exposes no `Ping()`, so this
 *   is derived from the safe in-process liveness signal
 *   (`isAlive() && listenerReady()`) per decision 5, rather than opening any
 *   outbound socket from the diagnostics path.
 * - [XrayProviderProbeKind.StatApi] — HARD-GATED: the Xray gRPC stat API is only
 *   valid when the engine runs as a child process with the stat API enabled,
 *   which is NOT the Android in-process topology. It is NEVER invoked in-process;
 *   the runner returns a not-applicable [XrayProviderProbeResult] (`ok = false`)
 *   with a redacted explanation.
 *
 * Probes are skipped (not failed) when no provider is running, so the report
 * reflects "nothing to probe" rather than a false failure.
 */
internal class XrayProviderDiagnosticsProbeRunner(
    private val bridge: XrayNativeBridge,
) {
    /**
     * Run the in-process provider probes against the active provider and fold the
     * results into [baseSnapshot]. [providerState] gates execution: probes only
     * run when the provider is [VpnProviderState.Running].
     */
    fun run(
        providerState: VpnProviderState,
        baseSnapshot: XrayProviderSnapshot,
    ): XrayProviderProbeReport {
        if (providerState != VpnProviderState.Running) {
            return XrayProviderProbeReport(snapshot = baseSnapshot, probes = emptyList())
        }

        val probes =
            listOf(
                runVersion(),
                runListenerReadiness(),
                runWrapperPing(),
                statApiNotApplicable(),
            )
        return XrayProviderProbeReport(snapshot = baseSnapshot, probes = probes)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runVersion(): XrayProviderProbeResult =
        runCatching { bridge.version() }
            .fold(
                onSuccess = { version ->
                    XrayProviderProbeResult(
                        kind = XrayProviderProbeKind.Version,
                        ok = version.isNotBlank(),
                        detailRedacted = null,
                    )
                },
                onFailure = { error ->
                    XrayProviderProbeResult(
                        kind = XrayProviderProbeKind.Version,
                        ok = false,
                        detailRedacted = redact(error.message ?: "version read failed"),
                    )
                },
            )

    @Suppress("TooGenericExceptionCaught")
    private fun runListenerReadiness(): XrayProviderProbeResult =
        runCatching { bridge.listenerReady() }
            .fold(
                onSuccess = { ready ->
                    XrayProviderProbeResult(
                        kind = XrayProviderProbeKind.ListenerReadiness,
                        ok = ready,
                        detailRedacted = if (ready) null else redact("local inbound not accepting"),
                    )
                },
                onFailure = { error ->
                    XrayProviderProbeResult(
                        kind = XrayProviderProbeKind.ListenerReadiness,
                        ok = false,
                        detailRedacted = redact(error.message ?: "listener readiness read failed"),
                    )
                },
            )

    @Suppress("TooGenericExceptionCaught")
    private fun runWrapperPing(): XrayProviderProbeResult =
        runCatching { bridge.isAlive() && bridge.listenerReady() }
            .fold(
                onSuccess = { alive ->
                    XrayProviderProbeResult(
                        kind = XrayProviderProbeKind.WrapperPing,
                        ok = alive,
                        detailRedacted = if (alive) null else redact("engine not alive or inbound down"),
                    )
                },
                onFailure = { error ->
                    XrayProviderProbeResult(
                        kind = XrayProviderProbeKind.WrapperPing,
                        ok = false,
                        detailRedacted = redact(error.message ?: "wrapper ping failed"),
                    )
                },
            )

    /**
     * StatApi is never invoked in-process. Returns a not-applicable result so the
     * UI can render "n/a" distinctly from a failure.
     */
    private fun statApiNotApplicable(): XrayProviderProbeResult =
        XrayProviderProbeResult(
            kind = XrayProviderProbeKind.StatApi,
            ok = false,
            detailRedacted =
                redact("stat API requires the child-process gRPC topology; not applicable in-process"),
        )

    private fun redact(text: String): String = XrayProfileRedactor.redactText(text)
}
