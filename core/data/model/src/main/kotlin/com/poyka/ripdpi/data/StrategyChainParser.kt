package com.poyka.ripdpi.data

private const val TcpSection = "tcp"
private const val UdpSection = "udp"
private const val MaxTcpActivationThreshold = 65_535

fun parseStrategyChainDsl(source: String): Result<StrategyChainSet> =
    runCatching { StrategyChainDslParser(source).parse() }

private class StrategyChainDslParser(
    private val source: String,
) {
    private val tcpSteps = mutableListOf<TcpChainStepModel>()
    private val udpSteps = mutableListOf<UdpChainStepModel>()
    private var section = TcpSection

    fun parse(): StrategyChainSet {
        source.lineSequence().forEachIndexed { index, rawLine -> parseLine(rawLine, index + 1) }
        validateTcpChain(tcpSteps)
        validateUdpChain(udpSteps)
        return StrategyChainSet(tcpSteps = tcpSteps, udpSteps = udpSteps)
    }

    private fun parseLine(
        rawLine: String,
        lineNumber: Int,
    ) {
        val line = rawLine.substringBefore('#').trim()
        if (line.isEmpty() || selectSection(line)) return
        val parts = line.split(Regex("\\s+"), limit = 2)
        require(parts.size == 2) { "Invalid chain step on line $lineNumber" }
        when (section) {
            TcpSection -> tcpSteps += parseTcpStep(parseTcpKind(parts[0], lineNumber), parts[1], lineNumber)
            UdpSection -> udpSteps += parseUdpStep(parseUdpKind(parts[0], lineNumber), parts[1], lineNumber)
            else -> error("Unknown chain section '$section'")
        }
    }

    private fun selectSection(line: String): Boolean =
        when (line.lowercase()) {
            "[tcp]" -> true.also { section = TcpSection }
            "[udp]" -> true.also { section = UdpSection }
            else -> false
        }

    private fun parseTcpKind(
        wireName: String,
        lineNumber: Int,
    ): TcpChainStepKind =
        TcpChainStepKind.fromWireName(wireName) ?: error("Unknown TCP step '$wireName' on line $lineNumber")

    private fun parseUdpKind(
        wireName: String,
        lineNumber: Int,
    ): UdpChainStepKind =
        UdpChainStepKind.fromWireName(wireName) ?: error("Unknown UDP step '$wireName' on line $lineNumber")
}

private fun parseUdpStep(
    kind: UdpChainStepKind,
    spec: String,
    lineNumber: Int,
): UdpChainStepModel {
    val tokens = spec.split(Regex("\\s+")).filter(String::isNotBlank)
    val valueLabel = if (kind == UdpChainStepKind.IpFrag2Udp) "splitBytes" else "count"
    val primaryValue = tokens.firstOrNull()?.toIntOrNull() ?: error("Invalid UDP $valueLabel on line $lineNumber")
    require(primaryValue >= 0) { "Invalid UDP $valueLabel on line $lineNumber" }
    var activationFilter = ActivationFilterModel()
    var ipv6ExtensionProfile = StrategyIpv6ExtensionProfileNone
    tokens.drop(1).forEach { token ->
        val (key, value) = parseStepOption(token, "UDP", lineNumber)
        if (key.equals("ipv6ext", ignoreCase = true)) {
            require(kind == UdpChainStepKind.IpFrag2Udp) {
                "ipv6ext is only supported for ipfrag2_udp on line $lineNumber"
            }
            ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(value)
        } else {
            activationFilter = parseActivationToken(activationFilter, key, value, lineNumber, false)
        }
    }
    return normalizeUdpChainStepModel(
        UdpChainStepModel(
            kind = kind,
            count = if (kind == UdpChainStepKind.IpFrag2Udp) 0 else primaryValue,
            splitBytes = if (kind == UdpChainStepKind.IpFrag2Udp) primaryValue else 0,
            activationFilter = activationFilter,
            ipv6ExtensionProfile = ipv6ExtensionProfile,
        ),
    )
}

private fun parseTcpStep(
    kind: TcpChainStepKind,
    spec: String,
    lineNumber: Int,
): TcpChainStepModel {
    val tokens = spec.split(Regex("\\s+")).filter { it.isNotBlank() }
    require(tokens.isNotEmpty()) { "Missing marker on line $lineNumber" }
    val marker = normalizeTcpMarker(kind, tokens.first())
    require(isValidOffsetExpression(marker)) { "Invalid marker on line $lineNumber" }
    require(kind.supportsAdaptiveMarker || !isAdaptiveOffsetExpression(marker)) {
        "Adaptive markers are not supported for ${kind.wireName} on line $lineNumber"
    }

    val draft = TcpStepDraft(kind, marker, lineNumber)
    tokens.drop(1).forEach(draft::applyOption)
    return draft.build()
}

private class TcpStepDraft(
    private val kind: TcpChainStepKind,
    private val marker: String,
    private val lineNumber: Int,
) {
    private var midhostMarker = ""
    private var fakeHostTemplate = ""
    private var fakeOrder = FakeOrderDefault
    private var fakeSeqMode = FakeSeqModeDuplicate
    private var overlapSize = 0
    private var overlapSpecified = false
    private var fakeMode = SeqOverlapFakeModeProfile
    private var fragmentCount = 0
    private var minFragmentSize = 0
    private var maxFragmentSize = 0
    private var ipv6ExtensionProfile = StrategyIpv6ExtensionProfileNone
    private var activationFilter = ActivationFilterModel()
    private var tcpFlagsSet = ""
    private var tcpFlagsUnset = ""
    private var tcpFlagsOrigSet = ""
    private var tcpFlagsOrigUnset = ""

    fun applyOption(token: String) {
        val (rawKey, value) = parseStepOption(token, "TCP", lineNumber)
        val key = rawKey.lowercase()
        val handled =
            applyHostOption(key, value) ||
                applyFakeOption(key, value) ||
                applyFragmentOption(key, value) ||
                applyTcpFlagOption(key, value) ||
                applyActivationOption(key, value)
        if (!handled) error("Unknown TCP step option '$rawKey' on line $lineNumber")
    }

    private fun applyHostOption(
        key: String,
        value: String,
    ): Boolean =
        when (key) {
            "midhost" -> {
                require(kind == TcpChainStepKind.HostFake) {
                    "midhost is only supported for hostfake on line $lineNumber"
                }
                val normalized = normalizeMidhostMarker(kind, value)
                require(normalized.isNotEmpty() && isValidOffsetExpression(normalized)) {
                    "Invalid midhost marker on line $lineNumber"
                }
                require(!isAdaptiveOffsetExpression(normalized)) {
                    "Adaptive markers are not supported for hostfake midhost on line $lineNumber"
                }
                midhostMarker = normalized
                true
            }

            "host" -> {
                require(kind == TcpChainStepKind.HostFake) {
                    "host template is only supported for hostfake on line $lineNumber"
                }
                val normalized = normalizeFakeHostTemplate(kind, value)
                require(normalized.isNotEmpty()) { "Invalid host template on line $lineNumber" }
                fakeHostTemplate = normalized
                true
            }

            else -> {
                false
            }
        }

    private fun applyFakeOption(
        key: String,
        value: String,
    ): Boolean =
        when (key) {
            "altorder" -> {
                require(kind.supportsFakeOrdering) {
                    "altorder is only supported for fake, fakedsplit, fakeddisorder, and hostfake on line $lineNumber"
                }
                val normalized = canonicalFakeOrder(value)
                require(normalized in SupportedFakeOrderValues) { "Invalid altorder on line $lineNumber" }
                fakeOrder = normalized
                true
            }

            "seqmode" -> {
                require(kind.supportsFakeOrdering) {
                    "seqmode is only supported for fake, fakedsplit, fakeddisorder, and hostfake on line $lineNumber"
                }
                val normalized = canonicalFakeSeqMode(value)
                require(
                    normalized == FakeSeqModeDuplicate || normalized == FakeSeqModeSequential,
                ) { "Invalid seqmode on line $lineNumber" }
                fakeSeqMode = normalized
                true
            }

            "overlap" -> {
                require(
                    kind == TcpChainStepKind.SeqOverlap,
                ) { "overlap is only supported for seqovl on line $lineNumber" }
                overlapSpecified = true
                overlapSize = value.toIntOrNull() ?: error("Invalid overlap on line $lineNumber")
                require(overlapSize in 1..MaxSeqOverlapSize) { "Invalid overlap on line $lineNumber" }
                true
            }

            "fake" -> {
                require(
                    kind == TcpChainStepKind.SeqOverlap,
                ) { "fake is only supported for seqovl on line $lineNumber" }
                val normalizedFakeMode = canonicalSeqOverlapFakeMode(value)
                require(normalizedFakeMode.isNotEmpty() && isValidSeqOverlapFakeMode(normalizedFakeMode)) {
                    "Invalid fake mode on line $lineNumber"
                }
                fakeMode = normalizedFakeMode
                true
            }

            else -> {
                false
            }
        }

    private fun applyFragmentOption(
        key: String,
        value: String,
    ): Boolean =
        when (key) {
            "count" -> {
                require(
                    kind == TcpChainStepKind.TlsRandRec,
                ) { "count is only supported for tlsrandrec on line $lineNumber" }
                fragmentCount = value.toIntOrNull() ?: error("Invalid count on line $lineNumber")
                true
            }

            "min" -> {
                require(
                    kind == TcpChainStepKind.TlsRandRec,
                ) { "min is only supported for tlsrandrec on line $lineNumber" }
                minFragmentSize = value.toIntOrNull() ?: error("Invalid min on line $lineNumber")
                true
            }

            "max" -> {
                require(
                    kind == TcpChainStepKind.TlsRandRec,
                ) { "max is only supported for tlsrandrec on line $lineNumber" }
                maxFragmentSize = value.toIntOrNull() ?: error("Invalid max on line $lineNumber")
                true
            }

            "ipv6ext" -> {
                require(kind == TcpChainStepKind.IpFrag2) {
                    "ipv6ext is only supported for ipfrag2 on line $lineNumber"
                }
                ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(value)
                true
            }

            else -> {
                false
            }
        }

    private fun applyTcpFlagOption(
        key: String,
        value: String,
    ): Boolean =
        when (key) {
            "tcp_flags" -> {
                tcpFlagsSet = normalizeTcpFlagMask(value)
                true
            }

            "tcp_flags_unset" -> {
                tcpFlagsUnset = normalizeTcpFlagMask(value)
                true
            }

            "tcp_flags_orig" -> {
                tcpFlagsOrigSet = normalizeTcpFlagMask(value)
                true
            }

            "tcp_flags_orig_unset" -> {
                tcpFlagsOrigUnset = normalizeTcpFlagMask(value)
                true
            }

            else -> {
                false
            }
        }

    private fun applyActivationOption(
        key: String,
        value: String,
    ): Boolean =
        when (key) {
            "when_round",
            "when_size",
            "when_stream",
            "tcp_has_ts",
            "tcp_has_ech",
            "tcp_window_lt",
            "tcp_mss_lt",
            -> {
                activationFilter =
                    parseActivationToken(
                        activationFilter = activationFilter,
                        key = key,
                        value = value,
                        lineNumber = lineNumber,
                        allowTcpStatePredicates = true,
                    )
                true
            }

            else -> {
                false
            }
        }

    fun build(): TcpChainStepModel {
        if (kind == TcpChainStepKind.TlsRandRec) {
            require(fragmentCount > 0) { "Missing count on line $lineNumber" }
            require(minFragmentSize > 0) { "Missing min on line $lineNumber" }
            require(maxFragmentSize > 0) { "Missing max on line $lineNumber" }
        }
        if (kind == TcpChainStepKind.SeqOverlap && !overlapSpecified) overlapSize = DefaultSeqOverlapSize
        return normalizeTcpChainStepModel(
            TcpChainStepModel(
                kind = kind,
                marker = marker,
                midhostMarker = midhostMarker,
                fakeHostTemplate = fakeHostTemplate,
                fakeOrder = fakeOrder,
                fakeSeqMode = fakeSeqMode,
                overlapSize = overlapSize,
                fakeMode = fakeMode,
                fragmentCount = fragmentCount,
                minFragmentSize = minFragmentSize,
                maxFragmentSize = maxFragmentSize,
                activationFilter = activationFilter,
                ipv6ExtensionProfile = ipv6ExtensionProfile,
                tcpFlagsSet = tcpFlagsSet,
                tcpFlagsUnset = tcpFlagsUnset,
                tcpFlagsOrigSet = tcpFlagsOrigSet,
                tcpFlagsOrigUnset = tcpFlagsOrigUnset,
            ),
        )
    }
}

private fun parseStepOption(
    token: String,
    protocol: String,
    lineNumber: Int,
): List<String> =
    token.split('=', limit = 2).takeIf { it.size == 2 }
        ?: error("Invalid $protocol step option '$token' on line $lineNumber")

private fun parseActivationToken(
    activationFilter: ActivationFilterModel,
    key: String,
    value: String,
    lineNumber: Int,
    allowTcpStatePredicates: Boolean,
): ActivationFilterModel {
    val normalizedKey = key.lowercase()
    return when (normalizedKey) {
        "when_round" -> {
            activationFilter.copy(round = parseRange(::parseRoundRange, value, "round", lineNumber))
        }

        "when_size" -> {
            activationFilter.copy(payloadSize = parseRange(::parsePayloadSizeRange, value, "payload size", lineNumber))
        }

        "when_stream" -> {
            activationFilter.copy(streamBytes = parseRange(::parseStreamBytesRange, value, "stream byte", lineNumber))
        }

        "tcp_has_ts", "tcp_has_ech", "tcp_window_lt", "tcp_mss_lt" -> {
            activationFilter.applyTcpStatePredicate(normalizedKey, value, lineNumber, allowTcpStatePredicates)
        }

        else -> {
            error("Unknown activation filter '$key' on line $lineNumber")
        }
    }
}

private fun <R> parseRange(
    parser: (String) -> R,
    value: String,
    label: String,
    lineNumber: Int,
): R = runCatching { parser(value) }.getOrElse { error("Invalid $label filter on line $lineNumber") }

private fun ActivationFilterModel.applyTcpStatePredicate(
    key: String,
    value: String,
    lineNumber: Int,
    allowTcpStatePredicates: Boolean,
): ActivationFilterModel {
    require(allowTcpStatePredicates) { "$key is only supported for tcp steps on line $lineNumber" }
    return when (key) {
        "tcp_has_ts" -> copy(tcpHasTimestamp = parseActivationBooleanToken(key, value, lineNumber))
        "tcp_has_ech" -> copy(tcpHasEch = parseActivationBooleanToken(key, value, lineNumber))
        "tcp_window_lt" -> copy(tcpWindowBelow = parseActivationThresholdToken(key, value, lineNumber))
        "tcp_mss_lt" -> copy(tcpMssBelow = parseActivationThresholdToken(key, value, lineNumber))
        else -> error("Unknown tcp state activation filter '$key' on line $lineNumber")
    }
}

private fun parseActivationBooleanToken(
    key: String,
    value: String,
    lineNumber: Int,
): Boolean =
    when (value.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> error("Invalid $key filter on line $lineNumber")
    }

private fun parseActivationThresholdToken(
    key: String,
    value: String,
    lineNumber: Int,
): Int {
    val parsed = value.toIntOrNull() ?: error("Invalid $key filter on line $lineNumber")
    require(parsed in 1..MaxTcpActivationThreshold) { "Invalid $key filter on line $lineNumber" }
    return parsed
}
