package com.poyka.ripdpi.data

private const val IpFragmentAlignmentBytes = 8
private const val MaxIpv4OctetValue = 255
private const val MaxIpv4LabelCount = 4

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

internal fun normalizeFakeHostTemplate(
    kind: TcpChainStepKind,
    template: String,
): String {
    if (kind != TcpChainStepKind.HostFake) return ""
    val trimmed = template.trim().trimEnd('.').lowercase()
    val validHostname =
        !hasInvalidHostnameStructure(trimmed) &&
            !containsInvalidHostnameChar(trimmed) &&
            trimmed.split('.').none { label -> label.isEmpty() || label.startsWith('-') || label.endsWith('-') }
    val ipv4Parts = trimmed.split('.')
    val isIpv4Literal =
        ipv4Parts.size == MaxIpv4LabelCount &&
            ipv4Parts.all { part ->
                part.toIntOrNull()?.let { value -> value in 0..MaxIpv4OctetValue && value.toString() == part } == true
            }
    return if (validHostname && !isIpv4Literal) trimmed else ""
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
