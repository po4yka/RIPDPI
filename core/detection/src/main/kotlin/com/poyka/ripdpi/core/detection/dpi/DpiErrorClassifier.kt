package com.poyka.ripdpi.core.detection.dpi

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

object DpiErrorClassifier {
    private const val MAX_CAUSE_DEPTH = 10

    private val tlsSpoofMessages =
        listOf(
            "wrong version number",
            "record overflow",
            "oversized",
            "record layer failure",
            "decode error",
            "decoding error",
            "illegal parameter",
        )

    fun classify(
        exception: Throwable,
        stage: ProbeStage,
    ): DpiProbeError {
        val chain = exception.causeChain()
        val fullText =
            chain.joinToString(separator = " | ") { cause ->
                "${cause::class.java.simpleName}: ${cause.message.orEmpty()}".lowercase()
            }

        classifyTlsMitm(chain, fullText)?.let { return it }
        classifyTlsSpoof(fullText)?.let { return it }
        classifyTlsAlert(fullText)?.let { return it }

        if (chain.any { cause -> cause is UnknownHostException } || fullText.hasAny(DNS_FAILURE_MESSAGES)) {
            return DpiProbeError.DnsFail
        }

        if (chain.any { cause -> cause is EOFException } || fullText.hasAny(EOF_MESSAGES)) {
            return if (stage == ProbeStage.TLS_HANDSHAKE) DpiProbeError.TlsRst else DpiProbeError.TlsEof
        }

        if (chain.any { cause -> cause is SocketTimeoutException } || fullText.hasAny(TIMEOUT_MESSAGES)) {
            return when (stage) {
                ProbeStage.TCP_CONNECT -> DpiProbeError.SynDrop

                ProbeStage.TLS_HANDSHAKE -> DpiProbeError.TlsDrop

                ProbeStage.SENDING_DATA,
                ProbeStage.READING_DATA,
                -> DpiProbeError.Unknown
            }
        }

        if (chain.any { cause -> cause is ConnectException } || fullText.hasAny(CONNECTION_REFUSED_MESSAGES)) {
            return DpiProbeError.Refused
        }

        if (fullText.hasAny(NETWORK_UNREACHABLE_MESSAGES)) {
            return DpiProbeError.NetUnreach
        }

        if (chain.any { cause -> cause is NoRouteToHostException } || fullText.hasAny(HOST_UNREACHABLE_MESSAGES)) {
            return DpiProbeError.HostUnreach
        }

        if (fullText.hasAny(CONNECTION_ABORTED_MESSAGES)) {
            return DpiProbeError.TcpAbort
        }

        if (fullText.hasAny(CONNECTION_RESET_MESSAGES)) {
            return if (stage == ProbeStage.TLS_HANDSHAKE) DpiProbeError.TlsRstTls else DpiProbeError.TcpRst
        }

        return DpiProbeError.Unknown
    }

    private fun classifyTlsMitm(
        chain: List<Throwable>,
        fullText: String,
    ): DpiProbeError? {
        val hasTlsVerificationException =
            chain.any { cause ->
                cause is SSLPeerUnverifiedException ||
                    (cause is SSLHandshakeException && fullText.hasAny(TLS_CERTIFICATE_MESSAGES))
            }
        return if (hasTlsVerificationException || fullText.hasAny(TLS_CERTIFICATE_MESSAGES)) {
            DpiProbeError.TlsMitm
        } else {
            null
        }
    }

    private fun classifyTlsSpoof(fullText: String): DpiProbeError? =
        if (fullText.hasAny(tlsSpoofMessages)) DpiProbeError.TlsSpoof else null

    private fun classifyTlsAlert(fullText: String): DpiProbeError? {
        val hasTlsError = fullText.contains("alert") || fullText.contains("sslexception")
        return when {
            !hasTlsError -> null

            fullText.contains("unrecognized_name") ||
                fullText.contains("unrecognized name") -> DpiProbeError.TlsAlertSni

            fullText.contains("handshake_failure") ||
                fullText.contains("handshake failure") -> DpiProbeError.TlsAlertHandshake

            fullText.contains("protocol_version") ||
                fullText.contains("alert_protocol_version") -> DpiProbeError.TlsBlockVersion

            else -> null
        }
    }

    private fun Throwable.causeChain(): List<Throwable> {
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = this
        repeat(MAX_CAUSE_DEPTH) {
            val cause = current ?: return causes
            causes += cause
            current = cause.cause
        }
        return causes
    }

    private fun String.hasAny(needles: Collection<String>): Boolean = needles.any { needle -> contains(needle) }

    private val DNS_FAILURE_MESSAGES =
        listOf(
            "getaddrinfo failed",
            "name resolution",
            "name or service not known",
            "nodename nor servname",
            "unknownhostexception",
            "eai_noname",
            "eai_again",
        )

    private val EOF_MESSAGES =
        listOf(
            "eof",
            "unexpected eof",
            "eof occurred",
            "operation did not complete",
            "want_read",
        )

    private val TIMEOUT_MESSAGES =
        listOf(
            "connect timeout",
            "connect timed out",
            "timed out",
            "etimedout",
            "errno=110",
            "errno 110",
        )

    private val CONNECTION_REFUSED_MESSAGES =
        listOf(
            "connection refused",
            "econnrefused",
            "errno=111",
            "errno 111",
            "wsaeconnrefused",
            "10061",
        )

    private val NETWORK_UNREACHABLE_MESSAGES =
        listOf(
            "network is unreachable",
            "enetunreach",
            "errno=101",
            "errno 101",
            "wsaenetunreach",
            "10051",
        )

    private val HOST_UNREACHABLE_MESSAGES =
        listOf(
            "no route to host",
            "host is unreachable",
            "ehostunreach",
            "errno=113",
            "errno 113",
            "wsaehostunreach",
            "10065",
        )

    private val CONNECTION_ABORTED_MESSAGES =
        listOf(
            "connection aborted",
            "econnaborted",
            "errno=103",
            "errno 103",
            "wsaeconnaborted",
            "10053",
        )

    private val CONNECTION_RESET_MESSAGES =
        listOf(
            "connection reset",
            "econnreset",
            "errno=104",
            "errno 104",
            "wsaeconnreset",
            "10054",
        )

    private val TLS_CERTIFICATE_MESSAGES =
        listOf(
            "certificate",
            "cert expired",
            "expired",
            "self-signed",
            "self signed",
            "unknown ca",
            "hostname mismatch",
            "peer not authenticated",
        )
}
