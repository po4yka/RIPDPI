package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.StrategyTcpStep
import com.poyka.ripdpi.proto.StrategyUdpStep

private fun StrategyTcpStep.toModelOrNull(): TcpChainStepModel? {
    val kind = TcpChainStepKind.fromWireName(kind) ?: return null
    return normalizeTcpChainStepModel(
        TcpChainStepModel(
            kind = kind,
            marker = normalizeTcpMarker(kind, marker),
            midhostMarker = normalizeMidhostMarker(kind, midhostMarker),
            fakeHostTemplate = normalizeFakeHostTemplate(kind, fakeHostTemplate),
            fakeOrder = normalizeFakeOrderForModel(kind, fakeOrder),
            fakeSeqMode = normalizeFakeSeqModeForModel(kind, fakeSeqMode),
            overlapSize = overlapSize,
            fakeMode = fakeMode,
            fragmentCount = fragmentCount,
            minFragmentSize = minFragmentSize,
            maxFragmentSize = maxFragmentSize,
            activationFilter = if (hasActivationFilter()) activationFilter.toModel() else ActivationFilterModel(),
            ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(ipv6ExtensionProfile),
            tcpFlagsSet = normalizeTcpFlagMask(tcpFlagsSet),
            tcpFlagsUnset = normalizeTcpFlagMask(tcpFlagsUnset),
            tcpFlagsOrigSet = normalizeTcpFlagMask(tcpFlagsOrigSet),
            tcpFlagsOrigUnset = normalizeTcpFlagMask(tcpFlagsOrigUnset),
        ),
    )
}

private fun StrategyUdpStep.toModelOrNull(): UdpChainStepModel? {
    val resolvedKind = UdpChainStepKind.fromWireName(kind) ?: return null
    return normalizeUdpChainStepModel(
        UdpChainStepModel(
            kind = resolvedKind,
            count = count,
            splitBytes = splitBytes,
            activationFilter = if (hasActivationFilter()) activationFilter.toModel() else ActivationFilterModel(),
            ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(ipv6ExtensionProfile),
        ),
    )
}

private fun TcpChainStepModel.toProto(): StrategyTcpStep =
    normalizeTcpChainStepModel(this).let { step ->
        StrategyTcpStep
            .newBuilder()
            .setKind(step.kind.wireName)
            .setMarker(normalizeTcpMarker(step))
            .setMidhostMarker(normalizeMidhostMarker(step.kind, step.midhostMarker))
            .setFakeHostTemplate(normalizeFakeHostTemplate(step.kind, step.fakeHostTemplate))
            .setFakeOrder(normalizeFakeOrderForProto(step.kind, step.fakeOrder))
            .setFakeSeqMode(normalizeFakeSeqModeForProto(step.kind, step.fakeSeqMode))
            .setOverlapSize(normalizeSeqOverlapSize(step.kind, step.overlapSize))
            .setFakeMode(normalizeSeqOverlapFakeModeForProto(step.kind, step.fakeMode))
            .setFragmentCount(normalizeFragmentCount(step.kind, step.fragmentCount))
            .setMinFragmentSize(normalizeMinFragmentSize(step.kind, step.minFragmentSize))
            .setMaxFragmentSize(normalizeMaxFragmentSize(step.kind, step.maxFragmentSize))
            .setIpv6ExtensionProfile(normalizeStrategyIpv6ExtensionProfile(step.ipv6ExtensionProfile))
            .setTcpFlagsSet(step.tcpFlagsSet)
            .setTcpFlagsUnset(step.tcpFlagsUnset)
            .setTcpFlagsOrigSet(step.tcpFlagsOrigSet)
            .setTcpFlagsOrigUnset(step.tcpFlagsOrigUnset)
            .apply {
                if (!step.activationFilter.isEmpty) {
                    setActivationFilter(step.activationFilter.toProto())
                }
            }.build()
    }

private fun UdpChainStepModel.toProto(): StrategyUdpStep =
    normalizeUdpChainStepModel(this).let { step ->
        StrategyUdpStep
            .newBuilder()
            .setKind(step.kind.wireName)
            .setCount(step.count.coerceAtLeast(0))
            .setSplitBytes(step.splitBytes.coerceAtLeast(0))
            .setIpv6ExtensionProfile(normalizeStrategyIpv6ExtensionProfile(step.ipv6ExtensionProfile))
            .apply {
                if (!step.activationFilter.isEmpty) {
                    setActivationFilter(step.activationFilter.toProto())
                }
            }.build()
    }

fun AppSettings.Builder.setStrategyChains(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): AppSettings.Builder =
    apply {
        validateTcpChain(tcpSteps)
        validateUdpChain(udpSteps)
        clearTcpChainSteps()
        tcpSteps.forEach { addTcpChainSteps(it.toProto()) }
        clearUdpChainSteps()
        udpSteps.forEach { addUdpChainSteps(it.toProto()) }
    }

fun AppSettings.Builder.setRawStrategyChainDsl(source: String): AppSettings.Builder {
    val parsed = parseStrategyChainDsl(source).getOrThrow()
    return setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
}

fun AppSettings.effectiveTcpChainSteps(): List<TcpChainStepModel> =
    if (tcpChainStepsCount > 0) {
        tcpChainStepsList.mapNotNull { it.toModelOrNull() }
    } else {
        emptyList()
    }

fun AppSettings.effectiveUdpChainSteps(): List<UdpChainStepModel> =
    if (udpChainStepsCount > 0) {
        udpChainStepsList.mapNotNull { it.toModelOrNull() }
    } else {
        emptyList()
    }

fun AppSettings.effectiveChainSummary(): String = formatChainSummary(effectiveTcpChainSteps(), effectiveUdpChainSteps())
