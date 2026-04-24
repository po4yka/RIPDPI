// ROADMAP-architecture-refactor W1 -- carry-over until Phase 1 slice 1.5
@file:Suppress("TooManyFunctions")

package com.poyka.ripdpi.data

private const val TcpSection = "tcp"
private const val UdpSection = "udp"
private const val MaxTcpActivationThreshold = 65_535
private const val IpFragmentAlignmentBytes = 8
private const val MaxIpv4OctetValue = 255
private const val MaxIpv4LabelCount = 4

// ROADMAP-architecture-refactor W1
@Suppress("LongMethod")
fun parseStrategyChainDsl(source: String): Result<StrategyChainSet> =
    runCatching {
        val tcpSteps = mutableListOf<TcpChainStepModel>()
        val udpSteps = mutableListOf<UdpChainStepModel>()
        var section = TcpSection

        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) {
                return@forEachIndexed
            }
            when (line.lowercase()) {
                "[tcp]" -> {
                    section = TcpSection
                    return@forEachIndexed
                }

                "[udp]" -> {
                    section = UdpSection
                    return@forEachIndexed
                }
            }

            val parts = line.split(Regex("\\s+"), limit = 2)
            require(parts.size == 2) { "Invalid chain step on line ${index + 1}" }
            when (section) {
                TcpSection -> {
                    val kind =
                        TcpChainStepKind.fromWireName(parts[0])
                            ?: error("Unknown TCP step '${parts[0]}' on line ${index + 1}")
                    tcpSteps += parseTcpStep(kind, parts[1], index + 1)
                }

                UdpSection -> {
                    val kind =
                        UdpChainStepKind.fromWireName(parts[0])
                            ?: error("Unknown UDP step '${parts[0]}' on line ${index + 1}")
                    val tokens = parts[1].split(Regex("\\s+")).filter { it.isNotBlank() }
                    val primaryValue =
                        tokens.firstOrNull()?.toIntOrNull()
                            ?: error(
                                if (kind == UdpChainStepKind.IpFrag2Udp) {
                                    "Invalid UDP splitBytes on line ${index + 1}"
                                } else {
                                    "Invalid UDP count on line ${index + 1}"
                                },
                            )
                    require(primaryValue >= 0) {
                        if (kind == UdpChainStepKind.IpFrag2Udp) {
                            "Invalid UDP splitBytes on line ${index + 1}"
                        } else {
                            "Invalid UDP count on line ${index + 1}"
                        }
                    }
                    var activationFilter = ActivationFilterModel()
                    var ipv6ExtensionProfile = StrategyIpv6ExtensionProfileNone
                    tokens.drop(1).forEach { token ->
                        val (key, value) =
                            token.split('=', limit = 2).takeIf { it.size == 2 }
                                ?: error("Invalid UDP step option '$token' on line ${index + 1}")
                        if (key.equals("ipv6ext", ignoreCase = true)) {
                            require(kind == UdpChainStepKind.IpFrag2Udp) {
                                "ipv6ext is only supported for ipfrag2_udp on line ${index + 1}"
                            }
                            ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(value)
                            return@forEach
                        }
                        activationFilter =
                            parseActivationToken(
                                activationFilter = activationFilter,
                                key = key,
                                value = value,
                                lineNumber = index + 1,
                                allowTcpStatePredicates = false,
                            )
                    }
                    udpSteps +=
                        normalizeUdpChainStepModel(
                            UdpChainStepModel(
                                kind = kind,
                                count = if (kind == UdpChainStepKind.IpFrag2Udp) 0 else primaryValue,
                                splitBytes = if (kind == UdpChainStepKind.IpFrag2Udp) primaryValue else 0,
                                activationFilter = activationFilter,
                                ipv6ExtensionProfile = ipv6ExtensionProfile,
                            ),
                        )
                }

                else -> {
                    error("Unknown chain section '$section'")
                }
            }
        }

        validateTcpChain(tcpSteps)
        validateUdpChain(udpSteps)
        StrategyChainSet(tcpSteps = tcpSteps, udpSteps = udpSteps)
    }

// ROADMAP-architecture-refactor W1
@Suppress("CyclomaticComplexMethod", "LongMethod")
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

    var midhostMarker = ""
    var fakeHostTemplate = ""
    var fakeOrder = FakeOrderDefault
    var fakeSeqMode = FakeSeqModeDuplicate
    var overlapSize = 0
    var overlapSpecified = false
    var fakeMode = SeqOverlapFakeModeProfile
    var fragmentCount = 0
    var minFragmentSize = 0
    var maxFragmentSize = 0
    var ipv6ExtensionProfile = StrategyIpv6ExtensionProfileNone
    var activationFilter = ActivationFilterModel()
    var tcpFlagsSet = ""
    var tcpFlagsUnset = ""
    var tcpFlagsOrigSet = ""
    var tcpFlagsOrigUnset = ""
    tokens.drop(1).forEach { token ->
        val (key, value) =
            token.split('=', limit = 2).takeIf { it.size == 2 }
                ?: error("Invalid TCP step option '$token' on line $lineNumber")
        when (key.lowercase()) {
            "midhost" -> {
                require(
                    kind == TcpChainStepKind.HostFake,
                ) { "midhost is only supported for hostfake on line $lineNumber" }
                val normalized = normalizeMidhostMarker(kind, value)
                require(normalized.isNotEmpty() && isValidOffsetExpression(normalized)) {
                    "Invalid midhost marker on line $lineNumber"
                }
                require(!isAdaptiveOffsetExpression(normalized)) {
                    "Adaptive markers are not supported for hostfake midhost on line $lineNumber"
                }
                midhostMarker = normalized
            }

            "host" -> {
                require(
                    kind == TcpChainStepKind.HostFake,
                ) { "host template is only supported for hostfake on line $lineNumber" }
                val normalized = normalizeFakeHostTemplate(kind, value)
                require(normalized.isNotEmpty()) { "Invalid host template on line $lineNumber" }
                fakeHostTemplate = normalized
            }

            "altorder" -> {
                require(kind.supportsFakeOrdering) {
                    "altorder is only supported for fake, fakedsplit, fakeddisorder, and hostfake on line $lineNumber"
                }
                val normalized = canonicalFakeOrder(value)
                require(normalized in SupportedFakeOrderValues) { "Invalid altorder on line $lineNumber" }
                fakeOrder = normalized
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
            }

            "overlap" -> {
                require(
                    kind == TcpChainStepKind.SeqOverlap,
                ) { "overlap is only supported for seqovl on line $lineNumber" }
                overlapSpecified = true
                overlapSize = value.toIntOrNull() ?: error("Invalid overlap on line $lineNumber")
                require(overlapSize in 1..MaxSeqOverlapSize) { "Invalid overlap on line $lineNumber" }
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
            }

            "count" -> {
                require(
                    kind == TcpChainStepKind.TlsRandRec,
                ) { "count is only supported for tlsrandrec on line $lineNumber" }
                fragmentCount = value.toIntOrNull() ?: error("Invalid count on line $lineNumber")
            }

            "min" -> {
                require(
                    kind == TcpChainStepKind.TlsRandRec,
                ) { "min is only supported for tlsrandrec on line $lineNumber" }
                minFragmentSize = value.toIntOrNull() ?: error("Invalid min on line $lineNumber")
            }

            "max" -> {
                require(
                    kind == TcpChainStepKind.TlsRandRec,
                ) { "max is only supported for tlsrandrec on line $lineNumber" }
                maxFragmentSize = value.toIntOrNull() ?: error("Invalid max on line $lineNumber")
            }

            "tcp_flags" -> {
                tcpFlagsSet = normalizeTcpFlagMask(value)
            }

            "tcp_flags_unset" -> {
                tcpFlagsUnset = normalizeTcpFlagMask(value)
            }

            "tcp_flags_orig" -> {
                tcpFlagsOrigSet = normalizeTcpFlagMask(value)
            }

            "tcp_flags_orig_unset" -> {
                tcpFlagsOrigUnset = normalizeTcpFlagMask(value)
            }

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
            }

            "ipv6ext" -> {
                require(kind == TcpChainStepKind.IpFrag2) {
                    "ipv6ext is only supported for ipfrag2 on line $lineNumber"
                }
                ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(value)
            }

            else -> {
                error("Unknown TCP step option '$key' on line $lineNumber")
            }
        }
    }

    if (kind == TcpChainStepKind.TlsRandRec) {
        require(fragmentCount > 0) { "Missing count on line $lineNumber" }
        require(minFragmentSize > 0) { "Missing min on line $lineNumber" }
        require(maxFragmentSize > 0) { "Missing max on line $lineNumber" }
    }
    if (kind == TcpChainStepKind.SeqOverlap && !overlapSpecified) {
        overlapSize = DefaultSeqOverlapSize
    }

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

internal fun normalizeTcpMarker(step: TcpChainStepModel): String = normalizeTcpMarker(step.kind, step.marker)

internal fun normalizeTcpMarker(
    kind: TcpChainStepKind,
    marker: String,
): String {
    val defaultValue = if (kind.isTlsPrelude) DefaultTlsRecordMarker else DefaultSplitMarker
    return normalizeOffsetExpression(marker, defaultValue)
}

internal fun normalizeSeqOverlapSize(
    kind: TcpChainStepKind,
    value: Int,
): Int = if (kind == TcpChainStepKind.SeqOverlap) value.takeIf { it > 0 } ?: DefaultSeqOverlapSize else 0

internal fun normalizeFakeOrderForProto(
    kind: TcpChainStepKind,
    value: String,
): String = if (kind.supportsFakeOrdering) normalizeFakeOrder(value) else ""

internal fun normalizeFakeOrderForModel(
    kind: TcpChainStepKind,
    value: String,
): String = if (kind.supportsFakeOrdering) normalizeFakeOrder(value) else FakeOrderDefault

internal fun normalizeFakeSeqModeForProto(
    kind: TcpChainStepKind,
    value: String,
): String = if (kind.supportsFakeOrdering) normalizeFakeSeqMode(value) else ""

internal fun normalizeFakeSeqModeForModel(
    kind: TcpChainStepKind,
    value: String,
): String = if (kind.supportsFakeOrdering) normalizeFakeSeqMode(value) else FakeSeqModeDuplicate

internal fun normalizeSeqOverlapFakeModeForProto(
    kind: TcpChainStepKind,
    value: String,
): String = if (kind == TcpChainStepKind.SeqOverlap) normalizeSeqOverlapFakeMode(value) else ""

internal fun normalizeFragmentCount(
    kind: TcpChainStepKind,
    value: Int,
): Int = if (kind == TcpChainStepKind.TlsRandRec) value.takeIf { it > 0 } ?: DefaultTlsRandRecFragmentCount else 0

internal fun normalizeMinFragmentSize(
    kind: TcpChainStepKind,
    value: Int,
): Int = if (kind == TcpChainStepKind.TlsRandRec) value.takeIf { it > 0 } ?: DefaultTlsRandRecMinFragmentSize else 0

internal fun normalizeMaxFragmentSize(
    kind: TcpChainStepKind,
    value: Int,
): Int = if (kind == TcpChainStepKind.TlsRandRec) value.takeIf { it > 0 } ?: DefaultTlsRandRecMaxFragmentSize else 0

fun normalizeTcpChainStepModel(step: TcpChainStepModel): TcpChainStepModel =
    step.copy(
        marker = normalizeTcpMarker(step.kind, step.marker),
        midhostMarker = normalizeMidhostMarker(step.kind, step.midhostMarker),
        fakeHostTemplate = normalizeFakeHostTemplate(step.kind, step.fakeHostTemplate),
        fakeOrder = normalizeFakeOrderForModel(step.kind, step.fakeOrder),
        fakeSeqMode = normalizeFakeSeqModeForModel(step.kind, step.fakeSeqMode),
        overlapSize = normalizeSeqOverlapSize(step.kind, step.overlapSize),
        fakeMode = normalizeSeqOverlapFakeModeForProto(step.kind, step.fakeMode),
        fragmentCount = normalizeFragmentCount(step.kind, step.fragmentCount),
        minFragmentSize = normalizeMinFragmentSize(step.kind, step.minFragmentSize),
        maxFragmentSize = normalizeMaxFragmentSize(step.kind, step.maxFragmentSize),
        activationFilter = normalizeActivationFilter(step.activationFilter),
        tcpFlagsSet = normalizeTcpFlagMask(step.tcpFlagsSet),
        tcpFlagsUnset = normalizeTcpFlagMask(step.tcpFlagsUnset),
        tcpFlagsOrigSet = normalizeTcpFlagMask(step.tcpFlagsOrigSet),
        tcpFlagsOrigUnset = normalizeTcpFlagMask(step.tcpFlagsOrigUnset),
        ipv6ExtensionProfile =
            if (step.kind == TcpChainStepKind.IpFrag2) {
                normalizeStrategyIpv6ExtensionProfile(step.ipv6ExtensionProfile)
            } else {
                StrategyIpv6ExtensionProfileNone
            },
    )

fun normalizeUdpChainStepModel(step: UdpChainStepModel): UdpChainStepModel =
    step.copy(
        count = if (step.kind == UdpChainStepKind.IpFrag2Udp) 0 else step.count.coerceAtLeast(0),
        splitBytes =
            if (step.kind == UdpChainStepKind.IpFrag2Udp) {
                roundIpFragmentBoundary(step.splitBytes)
            } else {
                0
            },
        activationFilter = normalizeActivationFilter(step.activationFilter),
        ipv6ExtensionProfile =
            if (step.kind == UdpChainStepKind.IpFrag2Udp) {
                normalizeStrategyIpv6ExtensionProfile(step.ipv6ExtensionProfile)
            } else {
                StrategyIpv6ExtensionProfileNone
            },
    )

private fun roundIpFragmentBoundary(value: Int): Int =
    if (value <= 0) {
        0
    } else {
        ((value + IpFragmentAlignmentBytes - 1) / IpFragmentAlignmentBytes) * IpFragmentAlignmentBytes
    }

// ROADMAP-architecture-refactor W1
@Suppress("ReturnCount")
internal fun normalizeFakeHostTemplate(
    kind: TcpChainStepKind,
    template: String,
): String {
    if (kind != TcpChainStepKind.HostFake) {
        return ""
    }
    val trimmed = template.trim().trimEnd('.').lowercase()
    if (hasInvalidHostnameStructure(trimmed)) {
        return ""
    }
    if (containsInvalidHostnameChar(trimmed)) {
        return ""
    }
    if (trimmed.split('.').any { label -> label.isEmpty() || label.startsWith('-') || label.endsWith('-') }) {
        return ""
    }
    val ipv4Parts = trimmed.split('.')
    val isIpv4Literal =
        ipv4Parts.size == MaxIpv4LabelCount &&
            ipv4Parts.all { part ->
                part.toIntOrNull()?.let { value -> value in 0..MaxIpv4OctetValue && value.toString() == part } == true
            }
    return if (isIpv4Literal) "" else trimmed
}

internal fun normalizeMidhostMarker(
    kind: TcpChainStepKind,
    marker: String,
): String = if (kind == TcpChainStepKind.HostFake) normalizeOffsetExpression(marker, "").trim() else ""

private fun hasInvalidHostnameStructure(trimmed: String): Boolean =
    trimmed.isEmpty() || trimmed.contains(':') || trimmed.startsWith('.') ||
        trimmed.endsWith('.') || trimmed.contains("..")

private fun containsInvalidHostnameChar(trimmed: String): Boolean =
    !trimmed.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '.' }

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
