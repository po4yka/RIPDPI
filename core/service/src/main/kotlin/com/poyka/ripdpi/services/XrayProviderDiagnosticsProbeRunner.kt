package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayListenerState
import com.poyka.ripdpi.data.xray.XrayProviderFailureClass
import com.poyka.ripdpi.data.xray.XrayProviderProbeKind
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.data.xray.XrayProviderProbeResult
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot

/** Reports cached native-worker observations; never invokes JNI or claims outbound reachability. */
internal class XrayProviderDiagnosticsProbeRunner {
    fun run(
        providerState: VpnProviderState,
        baseSnapshot: XrayProviderSnapshot,
    ): XrayProviderProbeReport {
        if (providerState != VpnProviderState.Running) {
            return XrayProviderProbeReport(snapshot = baseSnapshot, probes = emptyList())
        }
        val ready = baseSnapshot.listenerState == XrayListenerState.Bound
        return XrayProviderProbeReport(
            snapshot = baseSnapshot,
            probes =
                listOf(
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.Version,
                        ok = baseSnapshot.xrayVersion?.let { it.isNotBlank() && !it.endsWith("unknown") } == true,
                    ),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.ListenerReadiness,
                        ok = ready,
                        detailRedacted = "Cached local SOCKS5 readiness; no outbound probe",
                    ),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.WrapperPing,
                        ok = ready && baseSnapshot.failureClass == XrayProviderFailureClass.None,
                        detailRedacted = "Cached engine liveness; no remote ping",
                    ),
                    XrayProviderProbeResult(
                        XrayProviderProbeKind.StatApi,
                        ok = false,
                        detailRedacted = "Stat API is not configured; not applicable",
                    ),
                ),
        )
    }
}
