package com.poyka.ripdpi.diagnostics

internal enum class ResponseClass { Freeze, ServerResponse, ConnectionReset, ConnectionClosed, Complete, Other }

/** Why a row has no class: groups that do not describe the same thing must never share a row. */
internal enum class GroupSource(
    val token: String,
) {
    Catalog("catalog"),
    UserHost("user_host"),
    LegacyNoClass("legacy_no_class"),
    VolumeUnattributed("volume_unattributed"),
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

// Only a stalled or deadline-cut read is a freeze. A reset or a clean close shows an active peer or middlebox.
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
            // Multi-run readings are `|`-joined; a verified download has one run.
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

private fun ProbeResult.targetGroup(volumeTarget: SelectiveMatrixTarget?): TargetGroup? {
    val volumeLeg = detail("transferVolumeLeg") != null
    val targetClass = detail("targetClass")
    val asn = detail("destinationAsn")?.toLongOrNull()
    return when {
        targetClass != null -> {
            TargetGroup(targetClass, asn)
        }

        volumeLeg && volumeTarget?.targetClass != null -> {
            TargetGroup(
                volumeTarget.targetClass,
                volumeTarget.destinationAsn,
            )
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

/**
 * Groups freeze and related outcomes by target class and destination AS.
 * Volume-comparison legs carry no class, so the caller passes the catalog target their URL matched;
 * until a session path does that, legs report as `volume_unattributed`.
 */
internal fun List<ProbeResult>.groupByTargetClassAndAs(
    volumeTarget: SelectiveMatrixTarget? = null,
): List<TargetGroupCounts> =
    mapNotNull { result ->
        val group = result.targetGroup(volumeTarget)
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
