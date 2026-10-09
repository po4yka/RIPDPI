package com.poyka.ripdpi.diagnostics

/** Fixed fields only: never copy Android identifiers or exception messages. */
internal fun LocalNetworkContextModel.localNetworkSummaryLines(): List<String> {
    val safe = toSafeLocalNetworkContext()
    return with(safe) {
        listOf(
            "localNetwork.capturedAt=$capturedAt",
            "localNetwork.transport=${transport.name}",
            "localNetwork.airplaneMode=${device.airplaneMode.name}",
            "localNetwork.dataSaver=${device.dataSaver.name}",
            "localNetwork.backgroundRestricted=${device.backgroundRestricted.name}",
            "localNetwork.powerSaveMode=${device.powerSaveMode.name}",
            "localNetwork.batteryOptimizationExempt=${device.batteryOptimizationExempt.name}",
            "localNetwork.deviceIdle=${device.deviceIdle.name}",
            "localNetwork.metered=${device.metered.name}",
            "localNetwork.sim.scope=${sim.scope.name}",
            "localNetwork.sim.activeDataMatchesDefault=${sim.activeDataMatchesDefault.name}",
            "localNetwork.sim.state=${sim.simState.name}",
            "localNetwork.sim.mobileDataEnabled=${sim.mobileDataEnabled.name}",
            "localNetwork.sim.dataConnectionAllowed=${sim.dataConnectionAllowed.name}",
            "localNetwork.sim.roaming=${sim.roaming.name}",
            "localNetwork.sim.roamingEnabled=${sim.roamingEnabled.name}",
            "localNetwork.sim.voiceServiceState=${sim.voiceServiceState.name}",
            "localNetwork.sim.dataState=${sim.dataState.name}",
            "localNetwork.conditions=${localConstraintCodes().joinToString(",") { it.name }}",
            "localNetwork.evidenceScope=device_settings_and_default_data_sim",
            "localNetwork.causeValidated=false",
            "localNetwork.providerPolicy=unverified",
        )
    }
}
