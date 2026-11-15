package com.poyka.ripdpi.core.detection

val DetectionCheckResult.hasIncompleteChecks: Boolean
    get() =
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
        ).any { it.status in IncompleteStatuses }

private val IncompleteStatuses =
    setOf(DetectionCheckStatus.ERROR, DetectionCheckStatus.INCONCLUSIVE, DetectionCheckStatus.SKIPPED)
