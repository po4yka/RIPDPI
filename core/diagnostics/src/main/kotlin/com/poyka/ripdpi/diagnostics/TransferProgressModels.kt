package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TransferProgressSample(
    val elapsedMs: Long,
    val bodyByteCount: Long,
)

@Serializable
data class TransferMeasurement(
    val runIndex: Int,
    val runCount: Int,
    val receivedBodyByteCount: Long,
    val elapsedMs: Long,
    val expectedBodyByteCount: Long? = null,
    val firstBodyByteMs: Long? = null,
    val lastBodyProgressMs: Long? = null,
    val terminationReason: String? = null,
    val responseComplete: Boolean = false,
    val windowComplete: Boolean = false,
    val samples: List<TransferProgressSample> = emptyList(),
)

@Serializable
data class TransferProgress(
    val target: String,
    val measurement: TransferMeasurement,
)

@Serializable
data class TransferEvidence(
    @Required
    val version: Int = 1,
    val runs: List<TransferMeasurement>,
)

fun parseTransferEvidence(details: List<ProbeDetail>): TransferEvidence? =
    parseTransferEvidence(details.singleOrNull { it.key == "transferEvidence" }?.value)

fun parseTransferEvidence(value: String?): TransferEvidence? =
    value?.takeIf { it.length <= TransferEvidenceMaxCharacters }?.let {
        runCatching { TransferEvidenceJson.decodeFromString(TransferEvidence.serializer(), it) }
            .getOrNull()
            ?.takeIf { evidence ->
                evidence.version == 1 && evidence.runs.size in 1..TransferMaxRuns &&
                    evidence.runs.withIndex().all { (index, run) ->
                        run.runIndex == index + 1 && run.runCount == evidence.runs.first().runCount &&
                            run.terminationReason != null && run.isValidTransferMeasurement()
                    }
            }
    }

internal fun TransferProgress.validatedTransferProgress(): TransferProgress? =
    takeIf {
        target.isNotBlank() && target.length <= TransferTargetMaxCharacters &&
            measurement.isValidTransferMeasurement()
    }

internal fun canonicalTransferEvidence(value: String?): String? =
    parseTransferEvidence(value)?.let { TransferEvidenceJson.encodeToString(TransferEvidence.serializer(), it) }

private fun TransferMeasurement.isValidTransferMeasurement(): Boolean =
    runCount in 1..TransferMaxRuns && runIndex in 1..runCount && receivedBodyByteCount >= 0 && elapsedMs >= 0 &&
        (expectedBodyByteCount?.let { it >= receivedBodyByteCount } != false) &&
        (terminationReason == null || terminationReason in TransferTerminationReasons) &&
        hasValidBodyTiming() && hasValidSamples() && hasConsistentCompletion()

private fun TransferMeasurement.hasValidBodyTiming(): Boolean =
    if (receivedBodyByteCount == 0L) {
        firstBodyByteMs == null && lastBodyProgressMs == null
    } else {
        firstBodyByteMs != null && lastBodyProgressMs != null &&
            firstBodyByteMs >= 0 && firstBodyByteMs <= lastBodyProgressMs && lastBodyProgressMs <= elapsedMs
    }

private fun TransferMeasurement.hasValidSamples(): Boolean =
    samples.size <= TransferMaxSamples &&
        samples.all { it.elapsedMs in 0..elapsedMs && it.bodyByteCount in 0..receivedBodyByteCount } &&
        samples.zipWithNext().all { (before, after) ->
            before.elapsedMs <= after.elapsedMs && before.bodyByteCount <= after.bodyByteCount
        } &&
        (samples.lastOrNull()?.bodyByteCount ?: 0L) == receivedBodyByteCount

private fun TransferMeasurement.hasConsistentCompletion(): Boolean =
    responseComplete == (terminationReason in TransferCompleteReasons) &&
        (!windowComplete || receivedBodyByteCount > 0) &&
        (terminationReason != "window_limit" || windowComplete) &&
        (terminationReason != "content_length_complete" || expectedBodyByteCount == receivedBodyByteCount)

private const val TransferEvidenceMaxCharacters = 65_536
private const val TransferTargetMaxCharacters = 2_048
private const val TransferMaxRuns = 10
private const val TransferMaxSamples = 64
private val TransferEvidenceJson = Json { encodeDefaults = true }
private val TransferCompleteReasons = setOf("content_length_complete", "chunked_complete", "eof_complete")
private val TransferTerminationReasons =
    TransferCompleteReasons +
        setOf(
            "early_eof",
            "window_limit",
            "idle_timeout",
            "reset",
            "read_error",
            "invalid_framing",
            "cancelled",
            "deadline",
            "setup_error",
            "http_error",
        )
