package com.poyka.ripdpi.diagnostics

/** Only validated numeric measurements and known stop reasons enter the text export. */
internal fun transferEvidenceSummaryLines(results: List<ProbeResult>): List<String> =
    results
        .withIndex()
        .filter { it.value.probeType == "throughput_window" }
        .take(TransferSummaryTargetLimit)
        .flatMap { (index, result) ->
            parseTransferEvidence(result.details)?.runs.orEmpty().map { run ->
                val scope =
                    if (result.details.any { it.key == "transferNetworkScope" && it.value == "unverified" }) {
                        "unverified"
                    } else {
                        "scan_context"
                    }
                "transfer[$index].run=${run.runIndex}/${run.runCount} " +
                    "receivedBodyByteCount=${run.receivedBodyByteCount} " +
                    "expectedBodyByteCount=${run.expectedBodyByteCount ?: "unknown"} elapsedMs=${run.elapsedMs} " +
                    "firstBodyByteMs=${run.firstBodyByteMs ?: "unknown"} " +
                    "lastBodyProgressMs=${run.lastBodyProgressMs ?: "unknown"} " +
                    "terminationReason=${run.terminationReason} responseComplete=${run.responseComplete} " +
                    "windowComplete=${run.windowComplete} networkScope=$scope " +
                    "samplesMsBytes=${run.samples.joinToString(";") { "${it.elapsedMs}:${it.bodyByteCount}" }}"
            }
        }

private const val TransferSummaryTargetLimit = 10
