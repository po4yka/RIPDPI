package com.poyka.ripdpi.diagnostics

internal enum class ResponseClass { Freeze, ServerResponse, Complete, Other }

internal data class TargetGroup(
    val targetClass: String?,
    val destinationAsn: Int?,
)

internal data class TargetGroupCounts(
    val group: TargetGroup,
    val counts: Map<ResponseClass, Int>,
)

private const val UnattributedGroup = "unattributed"

// A stall reads as a freeze; any peer answer with another status, length or hash is a server response.
private val MatrixStallReasons = setOf("read_error", "cancelled")
private val FreezeOutcomes = setOf("tcp_freeze_after_threshold", "connection_freeze", "verified_download_timeout")
private val ServerResponseOutcomes =
    setOf("verified_download_size_mismatch", "verified_download_hash_mismatch", "verified_download_early_eof")

private fun ProbeResult.detail(key: String): String? = details.firstOrNull { it.key == key }?.value

internal fun ProbeResult.responseClass(): ResponseClass? {
    val bodyReason = detail("bodyReason")
    return when {
        outcome in FreezeOutcomes -> {
            ResponseClass.Freeze
        }

        outcome in ServerResponseOutcomes || outcome == "matrix_target_http_error" -> {
            ResponseClass.ServerResponse
        }

        outcome == "matrix_target_available" || outcome == "verified_download_complete" -> {
            ResponseClass.Complete
        }

        outcome == "matrix_target_body_incomplete" && bodyReason != null -> {
            if (bodyReason in MatrixStallReasons) ResponseClass.Freeze else ResponseClass.ServerResponse
        }

        outcome == "verified_download_failed" -> {
            val status = detail("httpStatusCodeReadings")?.substringBefore('|')?.toIntOrNull()
            if (status != null && status != 200) ResponseClass.ServerResponse else ResponseClass.Other
        }

        probeType == "selective_availability" || probeType == "throughput_window" -> {
            ResponseClass.Other
        }

        else -> {
            null
        }
    }
}

/**
 * Groups freeze and server-response outcomes by target class and destination AS.
 * Volume-comparison legs carry no class, so the caller passes the catalog target their URL matched.
 */
internal fun List<ProbeResult>.groupByTargetClassAndAs(
    volumeTarget: SelectiveMatrixTarget? = null,
): List<TargetGroupCounts> =
    mapNotNull { result ->
        val volumeLeg = result.detail("transferVolumeLeg") != null
        val attributed = result.detail("targetClass") != null
        if (!volumeLeg && !attributed && result.probeType != "selective_availability") return@mapNotNull null
        val fromVolume = volumeTarget.takeIf { volumeLeg && !attributed }
        val group =
            TargetGroup(
                result.detail("targetClass") ?: fromVolume?.targetClass,
                result.detail("destinationAsn")?.toIntOrNull() ?: fromVolume?.destinationAsn,
            )
        result.responseClass()?.let { group to it }
    }.groupBy({ it.first }, { it.second })
        .map { (group, classes) -> TargetGroupCounts(group, classes.groupingBy { it }.eachCount()) }
        .sortedWith(compareBy({ it.group.targetClass ?: "~" }, { it.group.destinationAsn ?: Int.MAX_VALUE }))

internal fun List<ProbeResult>.targetGroupSummaryLines(): List<String> =
    groupByTargetClassAndAs().map { row ->
        val counts = ResponseClass.entries.joinToString(" ") { "${it.name.lowercase()}=${row.counts[it] ?: 0}" }
        "targetGroup class=${row.group.targetClass ?: UnattributedGroup} asn=${row.group.destinationAsn ?: UnattributedGroup} $counts"
    }
