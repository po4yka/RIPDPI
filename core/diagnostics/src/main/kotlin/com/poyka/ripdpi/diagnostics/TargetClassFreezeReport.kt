package com.poyka.ripdpi.diagnostics

internal enum class ResponseClass { Freeze, ServerResponse, ConnectionReset, ConnectionClosed, Complete, Other }

internal enum class GroupSource(
    val token: String,
) {
    Catalog("catalog"),
    UserHost("user_host"),
    LegacyNoClass("legacy_no_class"),
    VolumeUnattributed("volume_unattributed"),
    VolumeDeclared("volume_declared"),
}

internal data class TargetGroup(
    val targetClass: String?,
    val destinationAsn: Long?,
    val source: GroupSource = GroupSource.Catalog,
)

internal data class TargetGroupCounts(
    val group: TargetGroup,
    val counts: Map<ResponseClass, Int>,
)

private const val NoValue = "none"

private val MatrixStallReasons = setOf("read_timeout", "cancelled")
private val FreezeOutcomes = setOf("tcp_freeze_after_threshold", "connection_freeze", "verified_download_timeout")
private val ServerResponseOutcomes = setOf("verified_download_size_mismatch", "verified_download_hash_mismatch")

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

        outcome == "verified_download_early_eof" -> {
            ResponseClass.ConnectionClosed
        }

        outcome == "matrix_target_available" || outcome == "verified_download_complete" -> {
            ResponseClass.Complete
        }

        outcome == "matrix_target_body_incomplete" && bodyReason != null -> {
            matrixBodyClass(bodyReason)
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

private fun matrixBodyClass(reason: String): ResponseClass =
    when (reason) {
        in MatrixStallReasons -> ResponseClass.Freeze
        "connection_reset" -> ResponseClass.ConnectionReset
        "early_eof" -> ResponseClass.ConnectionClosed
        "read_error" -> ResponseClass.Other
        else -> ResponseClass.ServerResponse
    }

private fun ProbeResult.targetGroup(): TargetGroup? {
    val volumeLeg = detail("transferVolumeLeg") != null
    val targetClass = detail("targetClass")
    val asn = detail("destinationAsn")?.toLongOrNull()
    return when {
        volumeLeg && targetClass != null -> {
            TargetGroup(targetClass, asn, GroupSource.VolumeDeclared)
        }

        targetClass != null -> {
            TargetGroup(targetClass, asn)
        }

        volumeLeg -> {
            TargetGroup(null, null, GroupSource.VolumeUnattributed)
        }

        probeType != "selective_availability" -> {
            null
        }

        detail("cohort") == "user" -> {
            TargetGroup(null, null, GroupSource.UserHost)
        }

        else -> {
            TargetGroup(null, null, GroupSource.LegacyNoClass)
        }
    }
}

internal fun List<ProbeResult>.groupByTargetClassAndAs(): List<TargetGroupCounts> =
    mapNotNull { result ->
        val group = result.targetGroup()
        val responseClass = result.responseClass()
        if (group != null && responseClass != null) group to responseClass else null
    }.groupBy({ it.first }, { it.second })
        .map { (group, classes) -> TargetGroupCounts(group, classes.groupingBy { it }.eachCount()) }
        .sortedWith(
            compareBy({ it.group.source.ordinal }, { it.group.targetClass ?: "~" }, {
                it.group.destinationAsn
                    ?: Long.MAX_VALUE
            }),
        )

internal fun List<ProbeResult>.targetGroupSummaryLines(): List<String> =
    groupByTargetClassAndAs().map { row ->
        val counts = ResponseClass.entries.joinToString(" ") { "${it.name.lowercase()}=${row.counts[it] ?: 0}" }
        "targetGroup source=${row.group.source.token} class=${row.group.targetClass ?: NoValue} " +
            "asn=${row.group.destinationAsn ?: NoValue} $counts"
    }
