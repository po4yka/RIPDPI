package com.poyka.ripdpi.data

fun formatChainSummary(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): String =
    listOfNotNull(
        tcpSteps.takeIf { it.isNotEmpty() }?.joinToString(prefix = "tcp: ", separator = " -> ") {
            formatTcpStepSummary(it)
        },
        udpSteps.takeIf { it.isNotEmpty() }?.joinToString(prefix = "udp: ", separator = " -> ") {
            formatUdpStepSummary(it)
        },
    ).ifEmpty {
        listOf("tcp: none")
    }.joinToString(" | ")

fun formatStrategyChainDsl(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): String {
    val lines = mutableListOf<String>()
    if (tcpSteps.isNotEmpty()) {
        lines += "[tcp]"
        lines += tcpSteps.map(::formatTcpStepDsl)
    }
    if (udpSteps.isNotEmpty()) {
        if (lines.isNotEmpty()) {
            lines += ""
        }
        lines += "[udp]"
        lines += udpSteps.map(::formatUdpStepDsl)
    }
    return lines.joinToString("\n")
}

private fun formatTcpStepSummary(step: TcpChainStepModel): String =
    buildString {
        val normalized = normalizeTcpChainStepModel(step)
        append(normalized.kind.wireName)
        append('(')
        append(formatOffsetExpressionLabel(normalizeTcpMarker(normalized)))
        val normalizedMidhost = normalizeMidhostMarker(normalized.kind, normalized.midhostMarker)
        if (normalizedMidhost.isNotEmpty()) {
            append(" midhost=")
            append(normalizedMidhost)
        }
        val normalizedTemplate = normalizeFakeHostTemplate(normalized.kind, normalized.fakeHostTemplate)
        if (normalizedTemplate.isNotEmpty()) {
            append(" host=")
            append(normalizedTemplate)
        }
        if (normalized.kind.supportsFakeOrdering && normalized.fakeOrder != FakeOrderDefault) {
            append(" altorder=")
            append(normalized.fakeOrder)
        }
        if (normalized.kind.supportsFakeOrdering && normalized.fakeSeqMode != FakeSeqModeDuplicate) {
            append(" seqmode=")
            append(normalized.fakeSeqMode)
        }
        if (normalized.kind == TcpChainStepKind.SeqOverlap) {
            append(" overlap=")
            append(normalized.overlapSize)
            append(" fake=")
            append(normalized.fakeMode)
        }
        if (normalized.kind == TcpChainStepKind.TlsRandRec) {
            append(" count=")
            append(normalized.fragmentCount)
            append(" min=")
            append(normalized.minFragmentSize)
            append(" max=")
            append(normalized.maxFragmentSize)
        }
        appendTcpFlagSummary(
            builder = this,
            tcpFlagsSet = normalized.tcpFlagsSet,
            tcpFlagsUnset = normalized.tcpFlagsUnset,
            tcpFlagsOrigSet = normalized.tcpFlagsOrigSet,
            tcpFlagsOrigUnset = normalized.tcpFlagsOrigUnset,
        )
        if (normalized.ipv6ExtensionProfile != StrategyIpv6ExtensionProfileNone) {
            append(" ipv6ext=")
            append(normalized.ipv6ExtensionProfile)
        }
        val filterSummary = formatActivationFilterSummary(normalized.activationFilter)
        if (filterSummary.isNotBlank()) {
            append(' ')
            append(filterSummary)
        }
        append(')')
    }

private fun formatTcpStepDsl(step: TcpChainStepModel): String =
    buildString {
        val normalized = normalizeTcpChainStepModel(step)
        append(normalized.kind.wireName)
        append(' ')
        append(normalizeTcpMarker(normalized))
        val normalizedMidhost = normalizeMidhostMarker(normalized.kind, normalized.midhostMarker)
        if (normalizedMidhost.isNotEmpty()) {
            append(" midhost=")
            append(normalizedMidhost)
        }
        val normalizedTemplate = normalizeFakeHostTemplate(normalized.kind, normalized.fakeHostTemplate)
        if (normalizedTemplate.isNotEmpty()) {
            append(" host=")
            append(normalizedTemplate)
        }
        if (normalized.kind.supportsFakeOrdering && normalized.fakeOrder != FakeOrderDefault) {
            append(" altorder=")
            append(normalized.fakeOrder)
        }
        if (normalized.kind.supportsFakeOrdering && normalized.fakeSeqMode != FakeSeqModeDuplicate) {
            append(" seqmode=")
            append(normalized.fakeSeqMode)
        }
        if (normalized.kind == TcpChainStepKind.SeqOverlap) {
            append(" overlap=")
            append(normalized.overlapSize)
            append(" fake=")
            append(normalized.fakeMode)
        }
        if (normalized.kind == TcpChainStepKind.TlsRandRec) {
            append(" count=")
            append(normalized.fragmentCount)
            append(" min=")
            append(normalized.minFragmentSize)
            append(" max=")
            append(normalized.maxFragmentSize)
        }
        appendTcpFlagDsl(
            builder = this,
            tcpFlagsSet = normalized.tcpFlagsSet,
            tcpFlagsUnset = normalized.tcpFlagsUnset,
            tcpFlagsOrigSet = normalized.tcpFlagsOrigSet,
            tcpFlagsOrigUnset = normalized.tcpFlagsOrigUnset,
        )
        if (normalized.ipv6ExtensionProfile != StrategyIpv6ExtensionProfileNone) {
            append(" ipv6ext=")
            append(normalized.ipv6ExtensionProfile)
        }
        appendActivationDsl(this, normalized.activationFilter)
    }

private fun formatUdpStepSummary(step: UdpChainStepModel): String =
    buildString {
        val normalized = normalizeUdpChainStepModel(step)
        append(normalized.kind.wireName)
        append('(')
        if (normalized.kind == UdpChainStepKind.IpFrag2Udp) {
            append(normalized.splitBytes)
        } else {
            append(normalized.count.coerceAtLeast(0))
        }
        if (normalized.ipv6ExtensionProfile != StrategyIpv6ExtensionProfileNone) {
            append(" ipv6ext=")
            append(normalized.ipv6ExtensionProfile)
        }
        val filterSummary = formatActivationFilterSummary(normalized.activationFilter)
        if (filterSummary.isNotBlank()) {
            append(' ')
            append(filterSummary)
        }
        append(')')
    }

private fun formatUdpStepDsl(step: UdpChainStepModel): String =
    buildString {
        val normalized = normalizeUdpChainStepModel(step)
        append(normalized.kind.wireName)
        append(' ')
        if (normalized.kind == UdpChainStepKind.IpFrag2Udp) {
            append(normalized.splitBytes)
        } else {
            append(normalized.count.coerceAtLeast(0))
        }
        if (normalized.ipv6ExtensionProfile != StrategyIpv6ExtensionProfileNone) {
            append(" ipv6ext=")
            append(normalized.ipv6ExtensionProfile)
        }
        appendActivationDsl(this, normalized.activationFilter)
    }

private fun appendActivationDsl(
    builder: StringBuilder,
    activationFilter: ActivationFilterModel,
) {
    formatNumericRange(activationFilter.round)?.let {
        builder.append(" when_round=")
        builder.append(it)
    }
    formatNumericRange(activationFilter.payloadSize)?.let {
        builder.append(" when_size=")
        builder.append(it)
    }
    formatNumericRange(activationFilter.streamBytes)?.let {
        builder.append(" when_stream=")
        builder.append(it)
    }
    activationFilter.tcpHasTimestamp?.let {
        builder.append(" tcp_has_ts=")
        builder.append(it)
    }
    activationFilter.tcpHasEch?.let {
        builder.append(" tcp_has_ech=")
        builder.append(it)
    }
    activationFilter.tcpWindowBelow?.let {
        builder.append(" tcp_window_lt=")
        builder.append(it)
    }
    activationFilter.tcpMssBelow?.let {
        builder.append(" tcp_mss_lt=")
        builder.append(it)
    }
}

private fun appendTcpFlagSummary(
    builder: StringBuilder,
    tcpFlagsSet: String,
    tcpFlagsUnset: String,
    tcpFlagsOrigSet: String,
    tcpFlagsOrigUnset: String,
) {
    tcpFlagsSet.takeIf(String::isNotBlank)?.let {
        builder.append(" fake+=")
        builder.append(it)
    }
    tcpFlagsUnset.takeIf(String::isNotBlank)?.let {
        builder.append(" fake-=")
        builder.append(it)
    }
    tcpFlagsOrigSet.takeIf(String::isNotBlank)?.let {
        builder.append(" orig+=")
        builder.append(it)
    }
    tcpFlagsOrigUnset.takeIf(String::isNotBlank)?.let {
        builder.append(" orig-=")
        builder.append(it)
    }
}

private fun appendTcpFlagDsl(
    builder: StringBuilder,
    tcpFlagsSet: String,
    tcpFlagsUnset: String,
    tcpFlagsOrigSet: String,
    tcpFlagsOrigUnset: String,
) {
    tcpFlagsSet.takeIf(String::isNotBlank)?.let {
        builder.append(" tcp_flags=")
        builder.append(it)
    }
    tcpFlagsUnset.takeIf(String::isNotBlank)?.let {
        builder.append(" tcp_flags_unset=")
        builder.append(it)
    }
    tcpFlagsOrigSet.takeIf(String::isNotBlank)?.let {
        builder.append(" tcp_flags_orig=")
        builder.append(it)
    }
    tcpFlagsOrigUnset.takeIf(String::isNotBlank)?.let {
        builder.append(" tcp_flags_orig_unset=")
        builder.append(it)
    }
}
