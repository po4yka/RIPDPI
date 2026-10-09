package com.poyka.ripdpi.diagnostics

/** Project measured facts without joining independent connections or inferring provider intent. */
fun ProbeResult.toConnectionStageScale(): ConnectionStageScale? {
    val evidence = ConnectionStageEvidence(details)
    val lanes =
        when (probeType) {
            "selective_availability" -> {
                listOf(matrixConnectionLane(evidence, outcome))
            }

            "throughput_window" -> {
                transferConnectionLanes(evidence, parseTransferEvidence(details))
            }

            "domain_reachability" -> {
                tlsConnectionLanes(evidence) + httpConnectionLane(evidence, "httpStatus")
            }

            "strategy_https" -> {
                tlsConnectionLanes(evidence)
            }

            "strategy_http" -> {
                listOf(httpConnectionLane(evidence, "status", outcome = outcome))
            }

            "quic_reachability", "strategy_quic" -> {
                listOf(
                    quicConnectionLane(evidence["status"], evidence.duration("latencyMs")),
                )
            }

            "dns_integrity" -> {
                dnsConnectionLanes(evidence)
            }

            "tcp_fat_header" -> {
                listOf(tcpConnectionLane(evidence))
            }

            "service_reachability" -> {
                serviceConnectionLanes(evidence)
            }

            "circumvention_reachability" -> {
                circumventionConnectionLanes(evidence)
            }

            else -> {
                return null
            }
        }
    return ConnectionStageScale(lanes)
}

fun TransferProgress.toConnectionStageScale(): ConnectionStageScale =
    ConnectionStageScale(
        listOf(
            validatedTransferProgress()?.measurement?.let { transferConnectionLane(it) }
                ?: connectionLane(ConnectionLaneKind.TRANSFER, emptyList()),
        ),
    )
