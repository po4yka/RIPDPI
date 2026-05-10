package com.poyka.ripdpi.diagnostics.rkn

sealed class RknAggregateVerdict(
    open val headline: String,
    open val confidenceNote: String,
) {
    data object InconclusiveControlDown : RknAggregateVerdict(
        headline = "Inconclusive: control baseline is down",
        confidenceNote =
            "Can't separate censorship from a broken uplink without a working baseline. Try a different network, " +
                "or check the local connection.",
    )

    data object InconclusiveAllTimeout : RknAggregateVerdict(
        headline = "Inconclusive: all probes timed out",
        confidenceNote = "Cannot determine blocking status when every probe times out.",
    )

    data object NotBlockedOrVpn : RknAggregateVerdict(
        headline = "Not blocked or VPN/proxy active",
        confidenceNote =
            "All blacklisted sites loaded — either you're outside the blocked zone, or your VPN/proxy is " +
                "intercepting the traffic.",
    )

    data class InBlockedZoneHigh(
        val highConfidenceBlocked: Int,
        val effectiveTotal: Int,
    ) : RknAggregateVerdict(
            headline = "In blocked zone: high confidence",
            confidenceNote =
                "$highConfidenceBlocked/$effectiveTotal blacklist failures match high-confidence patterns " +
                    "(DNS poisoning confirmed by DoH, HTTP 451, known stub-page markers).",
        )

    data object InBlockedZoneMedium : RknAggregateVerdict(
        headline = "In blocked zone: medium confidence",
        confidenceNote =
            "Most blacklist failures match censorship patterns (TLS DPI, TCP RST), but those signals can also " +
                "be caused by server-side issues. A control vantage point would confirm.",
    )

    data object PartialBlocks : RknAggregateVerdict(
        headline = "Partial blocks detected",
        confidenceNote =
            "Mixed signals. May indicate selective filtering, a mix of real blocks and unrelated server issues, " +
                "or a CDN flake.",
    )
}

enum class RknVerdictColorToken {
    POSITIVE,
    WARNING,
    ERROR,
    MUTED,
}

data class VerdictLabel(
    val symbol: String,
    val text: String,
    val color: RknVerdictColorToken,
)

object RknVerdictLabel {
    fun format(
        verdict: RknVerdict,
        confidence: RknConfidence,
    ): VerdictLabel =
        when (verdict) {
            RknVerdict.OK -> {
                VerdictLabel(symbol = "✓", text = "OK", color = RknVerdictColorToken.POSITIVE)
            }

            RknVerdict.TIMEOUT,
            RknVerdict.DOWN,
            -> {
                VerdictLabel(symbol = "·", text = "DOWN", color = RknVerdictColorToken.MUTED)
            }

            RknVerdict.UNKNOWN -> {
                VerdictLabel(symbol = "?", text = "UNKNOWN", color = RknVerdictColorToken.MUTED)
            }

            RknVerdict.DNS_BLOCK,
            RknVerdict.TCP_RESET,
            RknVerdict.TLS_BLOCK,
            RknVerdict.HTTP_STUB,
            -> {
                blockedLabel(verdict, confidence)
            }
        }

    private fun blockedLabel(
        verdict: RknVerdict,
        confidence: RknConfidence,
    ): VerdictLabel {
        val text = verdict.blockLabelText()
        return when (confidence) {
            RknConfidence.HIGH -> {
                VerdictLabel(symbol = "✗", text = text, color = RknVerdictColorToken.ERROR)
            }

            RknConfidence.MEDIUM -> {
                VerdictLabel(symbol = "~", text = "LIKELY $text", color = RknVerdictColorToken.WARNING)
            }

            RknConfidence.LOW -> {
                VerdictLabel(symbol = "?", text = "$text?", color = RknVerdictColorToken.WARNING)
            }
        }
    }

    private fun RknVerdict.blockLabelText(): String =
        when (this) {
            RknVerdict.DNS_BLOCK -> {
                "DNS"
            }

            RknVerdict.TCP_RESET -> {
                "TCP RESET"
            }

            RknVerdict.TLS_BLOCK -> {
                "TLS DPI"
            }

            RknVerdict.HTTP_STUB -> {
                "HTTP STUB"
            }

            RknVerdict.OK,
            RknVerdict.TIMEOUT,
            RknVerdict.DOWN,
            RknVerdict.UNKNOWN,
            -> {
                name
            }
        }
}

object RknAggregateVerdictEngine {
    fun aggregate(
        whitelist: List<RknCheckResult>,
        blacklist: List<RknCheckResult>,
    ): RknAggregateVerdict {
        if (whitelist.isNotEmpty()) {
            val whiteOk = whitelist.count { it.verdict == RknVerdict.OK }
            if (whiteOk.toDouble() / whitelist.size < CONTROL_HEALTH_MIN) {
                return RknAggregateVerdict.InconclusiveControlDown
            }
        }

        val effectiveBlacklist = blacklist.filterNot { it.verdict == RknVerdict.TIMEOUT }
        if (effectiveBlacklist.isEmpty()) {
            return RknAggregateVerdict.InconclusiveAllTimeout
        }

        if (effectiveBlacklist.all { it.verdict == RknVerdict.OK }) {
            return RknAggregateVerdict.NotBlockedOrVpn
        }

        val blocked = effectiveBlacklist.filter { it.verdict.isBlockedVerdict() }
        val blockedRatio = blocked.size.toDouble() / effectiveBlacklist.size
        val highConfidenceBlocked = blocked.count { it.confidence == RknConfidence.HIGH }
        val highConfidenceRatio = highConfidenceBlocked.toDouble() / effectiveBlacklist.size

        return when {
            blockedRatio >= BLOCKED_THRESHOLD && highConfidenceRatio >= HIGH_CONFIDENCE_THRESHOLD -> {
                RknAggregateVerdict.InBlockedZoneHigh(
                    highConfidenceBlocked = highConfidenceBlocked,
                    effectiveTotal = effectiveBlacklist.size,
                )
            }

            blockedRatio >= BLOCKED_THRESHOLD -> {
                RknAggregateVerdict.InBlockedZoneMedium
            }

            blocked.isNotEmpty() -> {
                RknAggregateVerdict.PartialBlocks
            }

            else -> {
                RknAggregateVerdict.PartialBlocks
            }
        }
    }

    fun blockTypes(blacklist: List<RknCheckResult>): Map<RknVerdict, Int> =
        blacklist
            .asSequence()
            .filter { result -> result.verdict.isBlockedVerdict() }
            .groupingBy { result -> result.verdict }
            .eachCount()

    private fun RknVerdict.isBlockedVerdict(): Boolean =
        this == RknVerdict.DNS_BLOCK ||
            this == RknVerdict.TCP_RESET ||
            this == RknVerdict.TLS_BLOCK ||
            this == RknVerdict.HTTP_STUB

    private const val CONTROL_HEALTH_MIN = 0.5
    private const val BLOCKED_THRESHOLD = 0.7
    private const val HIGH_CONFIDENCE_THRESHOLD = 0.5
}
