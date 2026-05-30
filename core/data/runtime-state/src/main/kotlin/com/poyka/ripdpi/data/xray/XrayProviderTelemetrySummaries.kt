package com.poyka.ripdpi.data.xray

/**
 * Builds redacted, share-safe text summaries of Xray provider diagnostics.
 *
 * Every summary is safe to drop into a bug report, a diagnostics export, or a
 * log line: it carries states, versions, latency numbers, and error *classes*
 * only. Any free-form detail is funnelled through
 * [XrayProfileRedactor.redactText] so a server address, UUID, REALITY key, or
 * live `host:port` that leaked into an error string is scrubbed before it
 * reaches the summary. This reuses the task-2 redaction surface rather than
 * re-implementing a scrubber.
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
            append("v=").append(snapshot.xrayVersion ?: "unknown")
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
     * active profile is named (label only) and the local inbound is shown
     * (always loopback). The server endpoint, UUID, and keys are never present;
     * any failure detail is redacted via [XrayProfileRedactor.redactText].
     */
    fun export(report: XrayProviderProbeReport): String {
        val s = report.snapshot
        return buildString {
            appendLine("=== Xray provider ===")
            appendLine("schema: ${s.schemaVersion}")
            appendLine("version: ${s.xrayVersion ?: "unknown"}")
            appendLine("readiness: ${s.readiness.name}")
            appendLine("listener: ${s.listenerState.name}")
            appendLine("outbound: ${s.outboundHealth.name}")
            appendLine("failureClass: ${s.failureClass.name}")
            s.tunnelTopology?.let { appendLine("topology: $it") }
            s.profileName?.let { appendLine("profile: $it") }
            s.outboundProtocol?.let { appendLine("protocol: $it") }
            s.outboundSecurity?.let { appendLine("security: $it") }
            // Local inbound is loopback by construction — safe to render verbatim.
            if (s.localInboundListen != null && s.localInboundPort != null) {
                appendLine("localInbound: ${s.localInboundListen}:${s.localInboundPort}")
            }
            s.lastPingRttMs?.let { appendLine("lastPingMs: $it") }
            if (s.hasConfigErrors) {
                appendLine("configFindings:")
                s.configFindings.forEach { finding ->
                    // message comes from the validator (no secrets) but scrub anyway.
                    appendLine("  - ${finding.code} @ ${finding.path}: ${redact(finding.message)}")
                }
            }
            s.lastFailureDetailRedacted?.let {
                appendLine("lastFailure: ${redact(it)}")
            }
            if (report.probes.isNotEmpty()) {
                appendLine("probes:")
                report.probes.forEach { probe ->
                    append("  - ").append(probe.kind.name)
                    append(": ").append(if (probe.ok) "ok" else "fail")
                    probe.latencyMs?.let { append(" (").append(it).append("ms)") }
                    probe.detailRedacted?.let { append(" ").append(redact(it)) }
                    appendLine()
                }
                appendLine("verdict: ${if (report.allHealthy) "healthy" else "unhealthy"}")
            }
        }.trimEnd()
    }

    /**
     * Defensively scrub a string that is expected to already be safe. Idempotent
     * against [XrayProfileRedactor.redactText], so double-redaction is a no-op.
     */
    fun redact(text: String): String = XrayProfileRedactor.redactText(text)
}
