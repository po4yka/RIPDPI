package com.poyka.ripdpi.data

/**
 * Extra CONNECT headers for a NaiveProxy profile, in upstream `--extra-headers` format
 * (`Name1: value1\r\nName2: value2`). The rules mirror the `ripdpi-naiveproxy` helper,
 * which is the authority; values may be secrets and are never logged here.
 */
const val NaiveExtraHeadersMaxCount: Int = 16
const val NaiveExtraHeadersMaxBytes: Int = 4096

/** The highest upstream `insecure-concurrency`; more connections make the client easier to fingerprint. */
const val MaxNaiveConcurrency: Int = 4

private val NaiveReservedHeaderNames: Set<String> =
    setOf(
        "host",
        "proxy-authorization",
        "padding",
        "padding-type-request",
        "x-naive-path",
        "connection",
        "transfer-encoding",
        "content-length",
        "te",
        "upgrade",
        "keep-alive",
        "proxy-connection",
    )

private const val NaiveHeaderTokenSymbols = "!#$%&'*+-.^_`|~"

enum class NaiveExtraHeadersError { InvalidSyntax, ReservedName, TooLarge }

sealed interface NaiveExtraHeadersResult {
    /** [normalized] is the wire form: non-blank lines trimmed and joined by CRLF; empty when no headers. */
    data class Valid(
        val normalized: String,
        val values: List<String>,
    ) : NaiveExtraHeadersResult

    data class Invalid(
        val error: NaiveExtraHeadersError,
    ) : NaiveExtraHeadersResult
}

fun parseNaiveExtraHeaders(raw: String): NaiveExtraHeadersResult {
    val lines = raw.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
    // The helper caps the form Kotlin sends (normalized, CRLF-joined), so measure that form.
    val normalized =
        lines.joinToString("\r\n") { "${it.substringBefore(':').trim()}: ${it.substringAfter(':').trim()}" }
    val tooLarge =
        normalized.toByteArray(Charsets.UTF_8).size > NaiveExtraHeadersMaxBytes ||
            lines.size > NaiveExtraHeadersMaxCount
    val error = if (tooLarge) NaiveExtraHeadersError.TooLarge else lines.firstNotNullOfOrNull(::naiveHeaderLineError)
    return if (error != null) {
        NaiveExtraHeadersResult.Invalid(error)
    } else {
        NaiveExtraHeadersResult.Valid(
            normalized = normalized,
            values = lines.map { it.substringAfter(':').trim() }.filter { it.isNotEmpty() },
        )
    }
}

private fun naiveHeaderLineError(line: String): NaiveExtraHeadersError? {
    val name = line.substringBefore(':').trim()
    val value = line.substringAfter(':', "").trim()
    return when {
        ':' !in line || name.isEmpty() || !name.all { it.isNaiveHeaderTokenChar() } -> {
            NaiveExtraHeadersError.InvalidSyntax
        }

        name.lowercase() in NaiveReservedHeaderNames -> {
            NaiveExtraHeadersError.ReservedName
        }

        !value.all { it == '\t' || it in ' '..'~' } -> {
            NaiveExtraHeadersError.InvalidSyntax
        }

        else -> {
            null
        }
    }
}

private fun Char.isNaiveHeaderTokenChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in NaiveHeaderTokenSymbols
