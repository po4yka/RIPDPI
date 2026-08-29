package com.poyka.ripdpi.data.xray

/**
 * Builds redacted, share-safe text summaries of Xray provider diagnostics.
 *
 * Every summary is safe to drop into a bug report, a diagnostics export, or a
 * log line: it carries states, versions, latency numbers, and error *classes*
 * only. Profile labels, arbitrary version strings, and free-form details are
 * excluded because they can contain secrets even without a recognizable pattern.
 */
object XrayProviderTelemetrySummaries {
    /**
     * One-line provider summary for Home / quick diagnostics.
     *
     * Example:
     * `Xray[v=25.1.0 readiness=OutboundHealthy listener=Bound outbound=Reachable
     *  fail=None]`
     */
    fun summarize(snapshot: XrayProviderSnapshot): String =
        buildString {
            append("Xray[")
            append("v=").append(safeVersion(snapshot.xrayVersion))
            append(" readiness=").append(snapshot.readiness.name)
            append(" listener=").append(snapshot.listenerState.name)
            append(" outbound=").append(snapshot.outboundHealth.name)
            append(" fail=").append(snapshot.failureClass.name)
            snapshot.lastPingRttMs?.let { append(" pingMs=").append(it) }
            if (snapshot.hasConfigErrors) {
                append(" configErrors=").append(snapshot.configFindings.size)
            }
            append("]")
        }

    /**
     * Multi-line provider diagnostics block for an export / share sheet. The
     * local inbound is shown only when verified loopback. Profile labels and
     * arbitrary failure details are omitted; typed failure classes remain.
     */
    fun export(report: XrayProviderProbeReport): String {
        val s = report.snapshot
        return buildString {
            appendLine("=== Xray provider ===")
            appendLine("schema: ${s.schemaVersion}")
            appendLine("version: ${safeVersion(s.xrayVersion)}")
            appendLine("readiness: ${s.readiness.name}")
            appendLine("listener: ${s.listenerState.name}")
            appendLine("outbound: ${s.outboundHealth.name}")
            appendLine("failureClass: ${s.failureClass.name}")
            s.tunnelTopology
                ?.takeIf {
                    it in
                        setOf(
                            "TunToLocalInbound",
                            "LibXraySetTunFd",
                        )
                }?.let { appendLine("topology: $it") }
            s.outboundProtocol?.takeIf { it == "vless" }?.let { appendLine("protocol: $it") }
            s.outboundSecurity?.takeIf { it in setOf("reality", "tls") }?.let { appendLine("security: $it") }
            // Local inbound is loopback by construction — safe to render verbatim.
            if (s.localInboundListen == "127.0.0.1" && s.localInboundPort in 1..MaxLocalPort) {
                appendLine("localInbound: ${s.localInboundListen}:${s.localInboundPort}")
            }
            s.lastPingRttMs?.let { appendLine("lastPingMs: $it") }
            if (s.hasConfigErrors) {
                appendLine("configFindings:")
                s.configFindings.forEach { finding ->
                    // message comes from the validator (no secrets) but scrub anyway.
                    appendLine("  - ${finding.code}")
                }
            }
            s.lastFailureDetailRedacted?.let {
                appendLine("lastFailure: ${XrayProfileRedactor.REDACTED}")
            }
            if (report.probes.isNotEmpty()) {
                appendLine("probes:")
                report.probes.forEach { probe ->
                    append("  - ").append(probe.kind.name)
                    append(": ").append(if (probe.ok) "ok" else "fail")
                    probe.latencyMs?.let { append(" (").append(it).append("ms)") }
                    probe.detailRedacted?.let { append(" ").append(XrayProfileRedactor.REDACTED) }
                    appendLine()
                }
                appendLine("verdict: ${if (report.allHealthy) "healthy" else "unhealthy"}")
            }
        }.trimEnd()
    }

    private const val MaxLocalPort = 65_535

    private fun safeVersion(value: String?): String =
        value
            ?.removePrefix("Xray ")
            ?.removePrefix("v")
            ?.takeIf { Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(it) } ?: "unknown"

    /**
     * Defensively scrub a string that is expected to already be safe. Idempotent
     * against [XrayProfileRedactor.redactText], so double-redaction is a no-op.
     */
    fun redact(text: String): String = XrayProfileRedactor.redactText(text)
}
