package com.poyka.ripdpi.diagnostics

/** Only validated response codes, flags and counts enter the text export. */
internal fun dnsSemanticsSummaryLines(results: List<ProbeResult>): List<String> =
    results
        .withIndex()
        .filter { it.value.probeType == "dns_integrity" }
        .take(DnsSummaryTargetLimit)
        .flatMap { (index, result) ->
            listOf("udp" to "udpDnsResponse", "encrypted" to "encryptedDnsResponse").mapNotNull { (source, key) ->
                parseDnsResponseSemantics(result.details, key)?.let { evidence ->
                    "dns[$index].$source queryType=${evidence.queryType} outcome=${evidence.outcome} " +
                        "rcode=${evidence.rcode ?: "unknown"} truncated=${evidence.truncated ?: "unknown"} " +
                        "ttlMinSeconds=${evidence.ttlMinSeconds ?: "unknown"} " +
                        "ttlMaxSeconds=${evidence.ttlMaxSeconds ?: "unknown"} " +
                        "negativeTtlSeconds=${evidence.negativeTtlSeconds ?: "unknown"} " +
                        "cnameCount=${evidence.cnameTargets.size} edeCodes=${evidence.extendedDnsErrorCodes} " +
                        "authoritative=${evidence.authoritative ?: "unknown"} " +
                        "recursionAvailable=${evidence.recursionAvailable ?: "unknown"} " +
                        "authenticatedData=${evidence.authenticatedData ?: "unknown"} " +
                        "hasSoa=${evidence.hasSoa ?: "unknown"} providerInterference=unverified"
                }
            }
        }

private const val DnsSummaryTargetLimit = 32
