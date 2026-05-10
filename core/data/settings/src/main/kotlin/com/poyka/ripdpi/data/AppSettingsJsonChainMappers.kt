package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.StrategyTcpStep
import com.poyka.ripdpi.proto.StrategyUdpStep

internal fun AppSettings.toTcpChainSnapshots(): List<AppSettingsTcpChainSnapshot> =
    tcpChainStepsList.map {
        AppSettingsTcpChainSnapshot(
            kind = it.kind,
            marker = it.marker,
            midhostMarker = it.midhostMarker.takeIf(String::isNotBlank),
            fakeHostTemplate = it.fakeHostTemplate.takeIf(String::isNotBlank),
            overlapSize = it.overlapSize.takeIf { value -> value > 0 },
            fakeMode =
                it.fakeMode.takeIf { value ->
                    value.isNotBlank() && value != SeqOverlapFakeModeProfile
                },
            fragmentCount = it.fragmentCount,
            minFragmentSize = it.minFragmentSize,
            maxFragmentSize = it.maxFragmentSize,
            activationFilter = if (it.hasActivationFilter()) it.activationFilter.toModel() else ActivationFilterModel(),
            ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(it.ipv6ExtensionProfile),
            tcpFlagsSet = it.tcpFlagsSet.takeIf(String::isNotBlank),
            tcpFlagsUnset = it.tcpFlagsUnset.takeIf(String::isNotBlank),
            tcpFlagsOrigSet = it.tcpFlagsOrigSet.takeIf(String::isNotBlank),
            tcpFlagsOrigUnset = it.tcpFlagsOrigUnset.takeIf(String::isNotBlank),
        )
    }

internal fun AppSettings.toUdpChainSnapshots(): List<AppSettingsUdpChainSnapshot> =
    udpChainStepsList.map {
        AppSettingsUdpChainSnapshot(
            kind = it.kind,
            count = it.count,
            splitBytes = it.splitBytes,
            activationFilter = if (it.hasActivationFilter()) it.activationFilter.toModel() else ActivationFilterModel(),
            ipv6ExtensionProfile = normalizeStrategyIpv6ExtensionProfile(it.ipv6ExtensionProfile),
        )
    }

internal fun AppSettings.Builder.applyChainSnapshots(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    also { builder ->
        snapshot.tcpChainSteps.forEach { step -> builder.addTcpChainStepSnapshot(step) }
        snapshot.udpChainSteps.forEach { step -> builder.addUdpChainStepSnapshot(step) }
    }

private fun AppSettings.Builder.addTcpChainStepSnapshot(step: AppSettingsTcpChainSnapshot) {
    addTcpChainSteps(
        StrategyTcpStep
            .newBuilder()
            .setKind(step.kind)
            .setMarker(step.marker)
            .setMidhostMarker(step.midhostMarker.orEmpty())
            .setFakeHostTemplate(step.fakeHostTemplate.orEmpty())
            .setOverlapSize(step.overlapSize ?: 0)
            .setFakeMode(step.fakeMode.orEmpty())
            .setFragmentCount(step.fragmentCount)
            .setMinFragmentSize(step.minFragmentSize)
            .setMaxFragmentSize(step.maxFragmentSize)
            .setIpv6ExtensionProfile(normalizeStrategyIpv6ExtensionProfile(step.ipv6ExtensionProfile))
            .setTcpFlagsSet(step.tcpFlagsSet.orEmpty())
            .setTcpFlagsUnset(step.tcpFlagsUnset.orEmpty())
            .setTcpFlagsOrigSet(step.tcpFlagsOrigSet.orEmpty())
            .setTcpFlagsOrigUnset(step.tcpFlagsOrigUnset.orEmpty())
            .apply {
                val normalizedFilter = normalizeActivationFilter(step.activationFilter)
                if (!normalizedFilter.isEmpty) {
                    setActivationFilter(normalizedFilter.toProto())
                }
            }.build(),
    )
}

private fun AppSettings.Builder.addUdpChainStepSnapshot(step: AppSettingsUdpChainSnapshot) {
    addUdpChainSteps(
        StrategyUdpStep
            .newBuilder()
            .setKind(step.kind)
            .setCount(step.count)
            .setSplitBytes(step.splitBytes)
            .setIpv6ExtensionProfile(normalizeStrategyIpv6ExtensionProfile(step.ipv6ExtensionProfile))
            .apply {
                val normalizedFilter = normalizeActivationFilter(step.activationFilter)
                if (!normalizedFilter.isEmpty) {
                    setActivationFilter(normalizedFilter.toProto())
                }
            }.build(),
    )
}
