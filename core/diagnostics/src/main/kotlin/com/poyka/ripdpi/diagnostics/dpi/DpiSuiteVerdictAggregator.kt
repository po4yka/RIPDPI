package com.poyka.ripdpi.diagnostics.dpi

object DpiSuiteVerdictAggregator {
    fun aggregate(results: List<DpiSuiteProbeResult>): DpiSuiteAggregate {
        val failures = results.count { result -> result is DpiSuiteProbeResult.Failed }
        if (results.isNotEmpty() && failures * 2 >= results.size) {
            return DpiSuiteAggregate(
                verdict = SuiteVerdict.INCONCLUSIVE,
                summaryLines = results.map(::summaryLine),
                results = results,
            )
        }

        val categories =
            buildSet {
                if (results.any(::hasDnsInterference)) add(SuiteVerdict.DNS_INTERFERENCE)
                if (results.any(::hasDpiDetection)) add(SuiteVerdict.DPI_DETECTED)
                if (results.any(::hasTelegramThrottling)) add(SuiteVerdict.THROTTLING)
            }
        val verdict =
            when {
                categories.size > 1 -> SuiteVerdict.MIXED
                categories.size == 1 -> categories.single()
                failures > 0 -> SuiteVerdict.INCONCLUSIVE
                else -> SuiteVerdict.CLEAN
            }
        return DpiSuiteAggregate(
            verdict = verdict,
            summaryLines = results.map(::summaryLine),
            results = results,
        )
    }

    private fun hasDnsInterference(result: DpiSuiteProbeResult): Boolean =
        result is DpiSuiteProbeResult.DnsIntegrity &&
            result.result.domains.any { domain ->
                domain.verdict != DnsIntegrityVerdict.DNS_OK && domain.verdict != DnsIntegrityVerdict.UNKNOWN
            }

    private fun hasDpiDetection(result: DpiSuiteProbeResult): Boolean =
        when (result) {
            is DpiSuiteProbeResult.DomainReachability -> {
                result.results.any { domain ->
                    domain.verdict != DomainVerdict.OK &&
                        domain.verdict != DomainVerdict.DNS_FAIL &&
                        domain.verdict != DomainVerdict.FAKE_IP
                }
            }

            is DpiSuiteProbeResult.Tcp16 -> {
                result.results.any { tcp -> tcp.verdict == Tcp16Verdict.DETECTED_AT_KB }
            }

            is DpiSuiteProbeResult.QuicH3 -> {
                result.results.any { quic ->
                    quic.verdict != QuicProbeVerdict.QUIC_OK && quic.verdict != QuicProbeVerdict.QUIC_TIMEOUT
                }
            }

            else -> {
                false
            }
        }

    private fun hasTelegramThrottling(result: DpiSuiteProbeResult): Boolean =
        result is DpiSuiteProbeResult.Telegram &&
            (result.result.verdict == TelegramTestVerdict.SLOW || result.result.verdict == TelegramTestVerdict.PARTIAL)

    private fun summaryLine(result: DpiSuiteProbeResult): String =
        when (result) {
            is DpiSuiteProbeResult.DnsIntegrity -> {
                val flagged = result.result.domains.count { domain -> domain.verdict != DnsIntegrityVerdict.DNS_OK }
                "DNS substitution: $flagged/${result.result.domains.size} flagged"
            }

            is DpiSuiteProbeResult.DnsAvailability -> {
                val available = result.results.count { server -> server.availableDomains > 0 }
                "DNS availability: $available/${result.results.size} resolvers available"
            }

            is DpiSuiteProbeResult.DomainReachability -> {
                val ok = result.results.count { domain -> domain.verdict == DomainVerdict.OK }
                val blocked = result.results.count { domain -> domain.verdict != DomainVerdict.OK }
                "Domains: $ok/${result.results.size} OK, $blocked flagged"
            }

            is DpiSuiteProbeResult.Tcp16 -> {
                val ok = result.results.count { tcp -> tcp.verdict == Tcp16Verdict.OK }
                val detected = result.results.count { tcp -> tcp.verdict == Tcp16Verdict.DETECTED_AT_KB }
                "TCP 16-20KB: $ok/${result.results.size} OK, $detected detected"
            }

            is DpiSuiteProbeResult.AllowlistSni -> {
                "Whitelist SNI: ${result.results.size} ASN groups"
            }

            is DpiSuiteProbeResult.Telegram -> {
                val reachable = result.result.dcResults.count { dc -> dc.reachable }
                "Telegram: ${result.result.verdict.name} · DC $reachable/${result.result.dcResults.size}"
            }

            is DpiSuiteProbeResult.QuicH3 -> {
                val ok = result.results.count { quic -> quic.verdict == QuicProbeVerdict.QUIC_OK }
                val flagged = result.results.count { quic -> quic.verdict != QuicProbeVerdict.QUIC_OK }
                "QUIC/H3: $ok/${result.results.size} OK, $flagged flagged"
            }

            is DpiSuiteProbeResult.Skipped -> {
                "${result.kind.name}: skipped (${result.reason})"
            }

            is DpiSuiteProbeResult.Failed -> {
                "${result.kind.name}: failed (${result.error})"
            }
        }
}
