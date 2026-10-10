package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

/** Device settings and default-data SIM facts; these do not identify the scan path. */
@Serializable
data class LocalNetworkContextModel(
    val capturedAt: Long = 0,
    val transport: LocalTransport = LocalTransport.UNKNOWN,
    val device: LocalDeviceConstraints = LocalDeviceConstraints(),
    val sim: LocalSimConstraints = LocalSimConstraints(),
)

@Serializable
data class LocalDeviceConstraints(
    val airplaneMode: LocalObservationState = LocalObservationState.UNKNOWN,
    val dataSaver: LocalDataSaverState = LocalDataSaverState.UNKNOWN,
    val backgroundRestricted: LocalObservationState = LocalObservationState.UNKNOWN,
    val powerSaveMode: LocalObservationState = LocalObservationState.UNKNOWN,
    val batteryOptimizationExempt: LocalObservationState = LocalObservationState.UNKNOWN,
    val deviceIdle: LocalObservationState = LocalObservationState.UNKNOWN,
    val metered: LocalObservationState = LocalObservationState.UNKNOWN,
)

@Serializable
data class LocalSimConstraints(
    val scope: LocalSimScope = LocalSimScope.UNAVAILABLE,
    val activeDataMatchesDefault: LocalObservationState = LocalObservationState.UNKNOWN,
    val simState: LocalSimState = LocalSimState.UNKNOWN,
    val mobileDataEnabled: LocalObservationState = LocalObservationState.UNKNOWN,
    val dataConnectionAllowed: LocalObservationState = LocalObservationState.UNKNOWN,
    val roaming: LocalObservationState = LocalObservationState.UNKNOWN,
    val roamingEnabled: LocalObservationState = LocalObservationState.UNKNOWN,
    val voiceServiceState: LocalServiceState = LocalServiceState.UNKNOWN,
    val dataState: LocalDataConnectionState = LocalDataConnectionState.UNKNOWN,
)

internal fun emptyLocalSim(scope: LocalSimScope): LocalSimConstraints {
    val value = if (scope.name in setOf("PERMISSION_DENIED", "UNSUPPORTED", "UNAVAILABLE")) scope.name else "UNKNOWN"
    return LocalSimConstraints(
        scope = scope,
        activeDataMatchesDefault = enumValueOf(value),
        simState = enumValueOf(value),
        mobileDataEnabled = enumValueOf(value),
        dataConnectionAllowed = enumValueOf(value),
        roaming = enumValueOf(value),
        roamingEnabled = enumValueOf(value),
        voiceServiceState = enumValueOf(value),
        dataState = enumValueOf(value),
    )
}
