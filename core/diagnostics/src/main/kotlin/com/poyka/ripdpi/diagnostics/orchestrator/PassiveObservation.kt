package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.diagnostics.shared.DnsClassification

/*
 * Phase 0 — passive observation from the last real failed flow.
 *
 * Before any active probing, the orchestrator extracts what it can from the
 * last failed flow so Phases 1 and 2 do not classify from zero. The observer
 * runs ONLY when a failed flow is present; on success paths the orchestrator
 * passes PassiveObservation.NONE and the observer does no work.
 *
 * See ripdpi-android-direct-mode-plan-2026-04-20 "Phase 0 — Passive
 * observation first".
 */

/** Phase of the last failed flow at which the failure first became observable. */
enum class FailPhase {
    /** No failure phase could be attributed (success path, or signal absent). */
    NONE,

    /** Failed before the TLS ClientHello left the device (e.g. TCP never established). */
    PRE_CLIENT_HELLO,

    /** Failed after the ClientHello was sent — handshake / post-SNI interference. */
    POST_CLIENT_HELLO,
}

/**
 * Coarse shape of a suspected block / error response.
 *
 * Deliberately a small, conservative heuristic set (plan: "starts
 * conservative, tuned from real captures"). Carries no host, IP, or other
 * network identifier — only the derived shape — so it is safe to persist and
 * log per [network-fingerprint-privacy].
 */
enum class ErrorPageShape {
    /** No error-page signal, or response looked like the real origin. */
    NONE,

    /** TLS terminated with a certificate that did not match the requested host. */
    TLS_CERT_MISMATCH,

    /** Response body looks like a middlebox-injected block page (HTML interstitial). */
    MIDDLEBOX_BLOCK_HTML,

    /** Body matched a known censor block-notice phrase. */
    KNOWN_BLOCK_PATTERN,

    /** Status / body size pattern typical of a legal-block stub (e.g. 451 + tiny body). */
    SIZE_ANOMALY,
}

/**
 * Typed passive observation extracted from the last failed flow.
 *
 * [observed] is false for [NONE] (nothing to learn from) and true whenever a
 * failed flow was supplied, even if every individual signal is neutral.
 */
data class PassiveObservation(
    val observed: Boolean,
    val dnsClassification: DnsClassification?,
    val failPhase: FailPhase,
    val udp443FailedWhileTcpWorked: Boolean,
    val errorPageShape: ErrorPageShape,
) {
    companion object {
        /** The "nothing observed" sentinel passed on success paths (zero cost). */
        val NONE: PassiveObservation =
            PassiveObservation(
                observed = false,
                dnsClassification = null,
                failPhase = FailPhase.NONE,
                udp443FailedWhileTcpWorked = false,
                errorPageShape = ErrorPageShape.NONE,
            )
    }
}

/**
 * Raw, already-captured signals from the last failed flow.
 *
 * The runtime fills this from a flow that actually failed; the orchestrator
 * never synthesises it. All fields are plain booleans / small scalars — no
 * host or address is carried into the observer.
 *
 * @param dnsClassification     DNS verdict for the flow, if the resolver step ran.
 * @param tcpHandshakeCompleted true when the TCP three-way handshake completed.
 * @param failedAfterClientHello true when failure occurred after ClientHello was sent.
 * @param udp443Failed          true when UDP/443 (QUIC) failed for the host.
 * @param tcpToSameHostSucceeded true when TCP to the same host worked while UDP/443 failed.
 * @param tlsCertificateMismatch true when the served certificate did not match the host.
 * @param responseStatusCode    HTTP status of any interstitial response, or null.
 * @param responseBodyBytes     byte length of any interstitial body, or null.
 * @param responseBodySnippet   short, lowercased text excerpt of the body, or null.
 */
data class LastFailedFlow(
    val dnsClassification: DnsClassification? = null,
    val tcpHandshakeCompleted: Boolean = false,
    val failedAfterClientHello: Boolean = false,
    val udp443Failed: Boolean = false,
    val tcpToSameHostSucceeded: Boolean = false,
    val tlsCertificateMismatch: Boolean = false,
    val responseStatusCode: Int? = null,
    val responseBodyBytes: Int? = null,
    val responseBodySnippet: String? = null,
)

/**
 * Pure Phase 0 observer: derives a [PassiveObservation] from a [LastFailedFlow].
 *
 * Contains only cheap classification of already-captured signals — it performs
 * no I/O and launches no probe, so it adds zero cost to success paths (where it
 * is called with `null`).
 */
class PassiveObserver {
    /**
     * Returns the passive observation for [flow], or [PassiveObservation.NONE]
     * when there is no failed flow to learn from.
     */
    fun observe(flow: LastFailedFlow?): PassiveObservation {
        if (flow == null) return PassiveObservation.NONE
        return PassiveObservation(
            observed = true,
            dnsClassification = flow.dnsClassification,
            failPhase = failPhaseOf(flow),
            udp443FailedWhileTcpWorked = flow.udp443Failed && flow.tcpToSameHostSucceeded,
            errorPageShape = errorPageShapeOf(flow),
        )
    }

    private fun failPhaseOf(flow: LastFailedFlow): FailPhase =
        when {
            flow.failedAfterClientHello -> FailPhase.POST_CLIENT_HELLO
            !flow.tcpHandshakeCompleted -> FailPhase.PRE_CLIENT_HELLO
            else -> FailPhase.NONE
        }

    private fun errorPageShapeOf(flow: LastFailedFlow): ErrorPageShape {
        val body = flow.responseBodySnippet?.lowercase()
        return when {
            flow.tlsCertificateMismatch -> ErrorPageShape.TLS_CERT_MISMATCH
            body != null && matchesKnownBlockPattern(body) -> ErrorPageShape.KNOWN_BLOCK_PATTERN
            body != null && looksLikeMiddleboxHtml(body) -> ErrorPageShape.MIDDLEBOX_BLOCK_HTML
            isLegalBlockStub(flow.responseStatusCode, flow.responseBodyBytes) -> ErrorPageShape.SIZE_ANOMALY
            else -> ErrorPageShape.NONE
        }
    }

    private fun matchesKnownBlockPattern(body: String): Boolean = KNOWN_BLOCK_PHRASES.any { body.contains(it) }

    private fun looksLikeMiddleboxHtml(body: String): Boolean =
        body.contains("<html") && MIDDLEBOX_MARKERS.any { body.contains(it) }

    private fun isLegalBlockStub(
        status: Int?,
        bodyBytes: Int?,
    ): Boolean {
        if (status == null) return false
        val legalBlockStatus = status == HTTP_UNAVAILABLE_FOR_LEGAL_REASONS || status == HTTP_FORBIDDEN
        return legalBlockStatus && (bodyBytes ?: 0) in 1 until SMALL_BODY_THRESHOLD_BYTES
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
        const val HTTP_UNAVAILABLE_FOR_LEGAL_REASONS = 451
        const val SMALL_BODY_THRESHOLD_BYTES = 512

        /** Conservative censor block-notice phrases (en + ru). All lowercase. */
        val KNOWN_BLOCK_PHRASES =
            listOf(
                "access denied",
                "this site is blocked",
                "website is not available",
                "доступ ограничен",
                "доступ к сайту ограничен",
                "запрещен на территории",
            )

        /** Markers typical of an injected middlebox HTML interstitial. All lowercase. */
        val MIDDLEBOX_MARKERS =
            listOf(
                "роскомнадзор",
                "firewall",
                "blocked by",
                "filtering",
            )
    }
}
