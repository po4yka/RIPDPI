package com.poyka.ripdpi.core.detection

fun DetectionCheckResult.hasIncompleteChecks(): Boolean =
    geoIp.executionStatus == CheckExecutionStatus.SKIPPED ||
        bypassResult.executionStatus in IncompleteStatuses ||
        listOfNotNull(
            geoIp,
            directSigns,
            indirectSigns,
            locationSignals,
            dnsLeak,
            webRtcLeak,
            tlsFingerprint,
            timingAnalysis,
            icmpSpoofing?.category,
            ipComparison?.category,
            rttTriangulation?.category,
            cdnPulling?.category,
            nativeSigns?.category,
            callTransport?.category,
            ipConsensus?.toCategoryResult(),
        ).any { it.executionStatus in IncompleteStatuses }

private val IncompleteStatuses = setOf(CheckExecutionStatus.ERROR, CheckExecutionStatus.INCONCLUSIVE)
