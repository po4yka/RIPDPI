package com.poyka.ripdpi.diagnostics

internal fun transferEvidenceSummaryLines(results: List<ProbeResult>): List<String> =
    results
        .withIndex()
        .filter { it.value.probeType == "throughput_window" }
        .take(TransferSummaryTargetLimit)
        .flatMap { (index, result) ->
            val runs = parseTransferEvidence(result.details)?.runs.orEmpty()
            val windows = parseTransferWindowReadings(result.details, runs)
            val scope =
                if (result.details.any { it.key == "transferNetworkScope" && it.value == "unverified" }) {
                    "unverified"
                } else {
                    "scan_context"
                }
            buildList {
                windows?.let {
                    val stopReason = it.remainingRunStopReason ?: "none"
                    add(
                        "transfer[$index].plannedRuns=${it.plannedRuns} attemptedRuns=${it.attemptedRuns} " +
                            "acceptedRuns=${it.acceptedRuns} remainingRunStopReason=$stopReason " +
                            "networkScope=$scope",
                    )
                }
                runs.forEachIndexed { runIndex, run ->
                    val expected = run.expectedBodyByteCount ?: "unknown"
                    val samples = run.samples.joinToString(";") { "${it.elapsedMs}:${it.bodyByteCount}" }
                    add(
                        "transfer[$index].run=${run.runIndex}/${run.runCount} " +
                            "receivedBodyByteCount=${run.receivedBodyByteCount} " +
                            "expectedBodyByteCount=$expected elapsedMs=${run.elapsedMs} " +
                            "firstBodyByteMs=${run.firstBodyByteMs ?: "unknown"} " +
                            "lastBodyProgressMs=${run.lastBodyProgressMs ?: "unknown"} " +
                            "terminationReason=${run.terminationReason} responseComplete=${run.responseComplete} " +
                            "windowComplete=${run.windowComplete} networkScope=$scope " +
                            "samplesMsBytes=$samples" +
                            windows
                                ?.let {
                                    " fullRequestRateBps=${it.fullRequestRatesBps.getOrNull(runIndex) ?: "unknown"} " +
                                        "bodyGoodputBps=${it.bodyGoodputRatesBps.getOrNull(runIndex) ?: "unknown"}"
                                }.orEmpty(),
                    )
                }
            }
        }

private const val TransferSummaryTargetLimit = 10
