package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.diagnostics.domain.ScanContext

internal fun strategyProbeContext(
    settings: com.poyka.ripdpi.proto.AppSettings,
    preferredDnsPath: EncryptedDnsPathCandidate?,
    preferredEdges: Map<String, List<PreferredEdgeCandidate>> = emptyMap(),
): ScanContext {
    val snapshot = FakeDiagnosticsContextProvider().captureContextForTest()
    return ScanContext(
        settings = settings,
        pathMode = ScanPathMode.RAW_PATH,
        networkFingerprint = null,
        preferredDnsPath = preferredDnsPath,
        preferredEdges = preferredEdges,
        networkSnapshot = null,
        serviceMode = Mode.VPN.name,
        contextSnapshot = snapshot,
        approachSnapshot = createStoredApproachSnapshot(diagnosticsTestJson(), settings, null, snapshot),
    )
}
