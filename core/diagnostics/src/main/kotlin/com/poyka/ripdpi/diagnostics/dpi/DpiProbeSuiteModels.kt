package com.poyka.ripdpi.diagnostics.dpi

enum class DpiProbeKind {
    DNS_INTEGRITY,
    DNS_AVAILABILITY,
    DOMAIN_REACHABILITY,
    TCP16,
    WHITELIST_SNI,
    TELEGRAM,
}

data class DpiSuiteConfig(
    val selection: Set<DpiProbeKind>,
    val customDomains: List<String>? = null,
    val concurrency: Int = DefaultConcurrency,
) {
    companion object {
        const val DefaultConcurrency = 100
    }
}

sealed interface DpiSuiteEvent {
    data class ProbeStarted(
        val kind: DpiProbeKind,
    ) : DpiSuiteEvent

    data class ProbeProgress(
        val kind: DpiProbeKind,
        val completed: Int,
        val total: Int,
    ) : DpiSuiteEvent

    data class ProbeCompleted(
        val kind: DpiProbeKind,
        val result: DpiSuiteProbeResult,
    ) : DpiSuiteEvent

    data class SuiteCompleted(
        val aggregate: DpiSuiteAggregate,
    ) : DpiSuiteEvent
}

sealed interface DpiSuiteProbeResult {
    val kind: DpiProbeKind

    data class DnsIntegrity(
        val result: DnsIntegrityResult,
    ) : DpiSuiteProbeResult {
        override val kind: DpiProbeKind = DpiProbeKind.DNS_INTEGRITY
    }

    data class DnsAvailability(
        val results: List<DnsServerResult>,
    ) : DpiSuiteProbeResult {
        override val kind: DpiProbeKind = DpiProbeKind.DNS_AVAILABILITY
    }

    data class DomainReachability(
        val results: List<DomainReachabilityResult>,
    ) : DpiSuiteProbeResult {
        override val kind: DpiProbeKind = DpiProbeKind.DOMAIN_REACHABILITY
    }

    data class Tcp16(
        val results: List<Tcp16ProbeResult>,
    ) : DpiSuiteProbeResult {
        override val kind: DpiProbeKind = DpiProbeKind.TCP16
    }

    data class AllowlistSni(
        val results: Map<String, AllowlistSniResult>,
    ) : DpiSuiteProbeResult {
        override val kind: DpiProbeKind = DpiProbeKind.WHITELIST_SNI
    }

    data class Telegram(
        val result: TelegramTestResult,
    ) : DpiSuiteProbeResult {
        override val kind: DpiProbeKind = DpiProbeKind.TELEGRAM
    }

    data class Skipped(
        override val kind: DpiProbeKind,
        val reason: String,
    ) : DpiSuiteProbeResult

    data class Failed(
        override val kind: DpiProbeKind,
        val error: String,
    ) : DpiSuiteProbeResult
}

enum class SuiteVerdict {
    CLEAN,
    DPI_DETECTED,
    DNS_INTERFERENCE,
    THROTTLING,
    MIXED,
    INCONCLUSIVE,
}

data class DpiSuiteAggregate(
    val verdict: SuiteVerdict,
    val summaryLines: List<String>,
    val results: List<DpiSuiteProbeResult>,
)
