package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.serialization.RipDpiJson

fun parseHttp3ProbeEvidence(details: List<ProbeDetail>): Http3ProbeEvidence? =
    parseHttp3ProbeEvidence(details.singleOrNull { it.key == "http3Evidence" }?.value)

fun parseHttp3ProbeEvidence(value: String?): Http3ProbeEvidence? =
    value?.takeIf { it.length <= Http3MaxEvidenceCharacters }?.let {
        runCatching { Http3EvidenceJson.decodeFromString(Http3ProbeEvidence.serializer(), it) }
            .getOrNull()
            ?.validatedHttp3ProbeEvidence()
    }

fun Http3ProbeEvidence.validatedHttp3ProbeEvidence(): Http3ProbeEvidence? =
    takeIf { validBounds() && validProgress() && validStages() && validOutcome() }

private fun Http3ProbeEvidence.validBounds(): Boolean {
    val times = listOfNotNull(handshakeElapsedMs, headersElapsedMs, firstByteElapsedMs, durationMs)
    return version == 1 && attemptCount in 0..Http3MaxAttempts && responseBytes in 0..Http3MaxResponseBytes &&
        (httpStatus == null || httpStatus in Http3FinalStatusRange) && (alpn == null || alpn == "h3") &&
        (reason == null || reason in Http3Reasons) &&
        (peerAddress == null || isIpProbeV4(peerAddress) || isIpProbeV6(peerAddress)) &&
        times.all { it in 0..Http3MaxElapsedMs } && times.zipWithNext().all { (before, after) -> before <= after }
}

private fun Http3ProbeEvidence.validProgress(): Boolean = validTransportProgress() && validResponseProgress()

private fun Http3ProbeEvidence.validTransportProgress(): Boolean =
    (!tlsValidated || (attemptCount > 0 && handshakeElapsedMs != null)) &&
        (!requestSent || (tlsValidated && alpn == "h3")) &&
        (!http3Validated || (requestSent && httpStatus != null && headersElapsedMs != null)) &&
        (!http3Validated || pathScope == Http3PathScope.RAW_PATH)

private fun Http3ProbeEvidence.validResponseProgress(): Boolean =
    (httpStatus == null || http3Validated) &&
        (!bodyComplete || http3Validated) &&
        (responseBytes == 0L || (http3Validated && firstByteElapsedMs != null)) &&
        (firstByteElapsedMs == null || responseBytes > 0L)

private fun Http3ProbeEvidence.validStages(): Boolean =
    (!tlsValidated || stage >= Http3ProbeStage.QUIC_HANDSHAKE) &&
        (!requestSent || stage >= Http3ProbeStage.HTTP_REQUEST) &&
        (!http3Validated || stage >= Http3ProbeStage.HTTP_HEADERS) &&
        ((responseBytes == 0L && !bodyComplete) || stage == Http3ProbeStage.HTTP_BODY) &&
        (status !in setOf(Http3ProbeStatus.UNSUPPORTED, Http3ProbeStatus.INVALID) || hasNoProtocolProgress())

private fun Http3ProbeEvidence.hasNoProtocolProgress(): Boolean =
    !tlsValidated && !requestSent && !http3Validated && !bodyComplete && responseBytes == 0L &&
        attemptCount == 0 && handshakeElapsedMs == null && headersElapsedMs == null && firstByteElapsedMs == null

private fun Http3ProbeEvidence.validOutcome(): Boolean =
    when (status) {
        Http3ProbeStatus.COMPLETE -> {
            bodyComplete && httpStatus in Http3SuccessRange &&
                stage == Http3ProbeStage.HTTP_BODY
        }

        Http3ProbeStatus.HTTP_ERROR -> {
            bodyComplete && httpStatus !in Http3SuccessRange &&
                stage == Http3ProbeStage.HTTP_BODY
        }

        Http3ProbeStatus.BODY_LIMIT -> {
            http3Validated && !bodyComplete && stage == Http3ProbeStage.HTTP_BODY
        }

        else -> {
            true
        }
    }

internal fun List<ProbeDetail>.withoutHttp3ScopeAuthority(): List<ProbeDetail> {
    val evidence =
        parseHttp3ProbeEvidence(
            this,
        )?.copy(status = Http3ProbeStatus.NOT_OBSERVED, reason = "network_scope_unverified")
    return filterNot { it.key == "http3Evidence" } +
        listOfNotNull(
            evidence?.let {
                ProbeDetail(
                    "http3Evidence",
                    Http3EvidenceJson.encodeToString(Http3ProbeEvidence.serializer(), it),
                )
            },
        )
}

internal fun canonicalHttp3ProbeEvidence(value: String?): String? =
    parseHttp3ProbeEvidence(value)?.let {
        Http3EvidenceJson.encodeToString(Http3ProbeEvidence.serializer(), it.copy(peerAddress = null))
    }

internal fun List<ProbeResult>.http3SummaryLines(): List<String> =
    asSequence()
        .filter { it.probeType == "http3" }
        .take(Http3MaxRows)
        .mapNotNull { result ->
            parseHttp3ProbeEvidence(result.details)?.let {
                "http3Validated=${it.http3Validated}; status=${it.status}; stage=${it.stage}; " +
                    "httpStatus=${it.httpStatus ?: "unknown"}; bytes=${it.responseBytes}; " +
                    "complete=${it.bodyComplete}; " +
                    "scope=${it.pathScope}; networkScopeUnverified=${it.reason == "network_scope_unverified"}"
            }
        }.toList()

internal fun bucketHttp3(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "http3_complete" -> DiagnosticsOutcomeBucket.Healthy
        "http3_http_error", "http3_incomplete", "http3_unavailable" -> DiagnosticsOutcomeBucket.Attention
        else -> DiagnosticsOutcomeBucket.Inconclusive
    }

private val Http3EvidenceJson = RipDpiJson
private const val Http3MaxEvidenceCharacters = 4_096
private const val Http3MaxAttempts = 4
private const val Http3MaxElapsedMs = 60_000L
private const val Http3MaxRows = 32
private val Http3FinalStatusRange = 200..599
private val Http3SuccessRange = 200..299
private val Http3Reasons =
    setOf(
        "dns_error",
        "dns_timeout",
        "no_addresses",
        "connect_error",
        "tls_error",
        "alpn_mismatch",
        "http3_error",
        "http_status_error",
        "body_limit",
        "timeout",
        "deadline_exceeded",
        "cancelled",
        "unsupported_path",
        "invalid_config",
        "protect_failed",
        "network_scope_unverified",
        "io_error",
    )
