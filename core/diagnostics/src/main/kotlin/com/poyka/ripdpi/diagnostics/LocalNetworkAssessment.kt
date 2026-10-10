package com.poyka.ripdpi.diagnostics

/** Observed conditions to review, not diagnoses of a provider restriction. */
enum class LocalConstraintCode {
    AIRPLANE_MODE,
    BACKGROUND_DATA_RESTRICTED,
    BACKGROUND_EXECUTION_RESTRICTED,
    POWER_SAVER,
    DEVICE_IDLE,
    BATTERY_OPTIMIZATION,
    NO_DEFAULT_DATA_SUBSCRIPTION,
    SUBSCRIPTION_CHANGED,
    ACTIVE_DATA_DIFFERS,
    SIM_NOT_READY,
    MOBILE_DATA_DISABLED,
    DATA_CONNECTION_DISALLOWED,
    ROAMING_DISABLED,
    VOICE_SERVICE_UNAVAILABLE,
    MOBILE_DATA_SUSPENDED,
    INCOMPLETE_EVIDENCE,
}

fun LocalNetworkContextModel.localConstraintCodes(): List<LocalConstraintCode> =
    buildList {
        addAll(device.localConstraintCodes())
        when (sim.scope) {
            LocalSimScope.DEFAULT_DATA -> addAll(sim.localConstraintCodes())
            LocalSimScope.NOT_CONFIGURED -> add(LocalConstraintCode.NO_DEFAULT_DATA_SUBSCRIPTION)
            LocalSimScope.CHANGED -> add(LocalConstraintCode.SUBSCRIPTION_CHANGED)
            else -> Unit
        }
        if (hasIncompleteEvidence()) add(LocalConstraintCode.INCOMPLETE_EVIDENCE)
    }

private fun LocalDeviceConstraints.localConstraintCodes(): List<LocalConstraintCode> =
    buildList {
        if (airplaneMode == LocalObservationState.ENABLED) add(LocalConstraintCode.AIRPLANE_MODE)
        if (dataSaver == LocalDataSaverState.ENABLED) add(LocalConstraintCode.BACKGROUND_DATA_RESTRICTED)
        if (backgroundRestricted ==
            LocalObservationState.ENABLED
        ) {
            add(LocalConstraintCode.BACKGROUND_EXECUTION_RESTRICTED)
        }
        if (powerSaveMode == LocalObservationState.ENABLED) add(LocalConstraintCode.POWER_SAVER)
        if (deviceIdle == LocalObservationState.ENABLED) add(LocalConstraintCode.DEVICE_IDLE)
        if (batteryOptimizationExempt == LocalObservationState.DISABLED) add(LocalConstraintCode.BATTERY_OPTIMIZATION)
    }

private fun LocalSimConstraints.localConstraintCodes(): List<LocalConstraintCode> =
    buildList {
        if (activeDataMatchesDefault == LocalObservationState.DISABLED) add(LocalConstraintCode.ACTIVE_DATA_DIFFERS)
        if (simState in UnreadySimStates) add(LocalConstraintCode.SIM_NOT_READY)
        if (mobileDataEnabled == LocalObservationState.DISABLED) add(LocalConstraintCode.MOBILE_DATA_DISABLED)
        if (dataConnectionAllowed == LocalObservationState.DISABLED &&
            mobileDataEnabled != LocalObservationState.DISABLED
        ) {
            add(LocalConstraintCode.DATA_CONNECTION_DISALLOWED)
        }
        if (roaming == LocalObservationState.ENABLED && roamingEnabled == LocalObservationState.DISABLED) {
            add(LocalConstraintCode.ROAMING_DISABLED)
        }
        if (voiceServiceState in UnregisteredServiceStates) add(LocalConstraintCode.VOICE_SERVICE_UNAVAILABLE)
        if (dataState == LocalDataConnectionState.SUSPENDED) add(LocalConstraintCode.MOBILE_DATA_SUSPENDED)
    }

private fun LocalNetworkContextModel.hasIncompleteEvidence(): Boolean {
    val deviceStates =
        with(device) {
            listOf(airplaneMode, backgroundRestricted, powerSaveMode, batteryOptimizationExempt, deviceIdle, metered)
        }
    val deviceIncomplete =
        deviceStates.any { it.name in MissingEvidenceNames } || device.dataSaver.name in MissingEvidenceNames
    val simIncomplete =
        when (sim.scope) {
            LocalSimScope.DEFAULT_DATA -> {
                with(sim) {
                    listOf(activeDataMatchesDefault, mobileDataEnabled, dataConnectionAllowed, roaming, roamingEnabled)
                        .any { it.name in MissingEvidenceNames } ||
                        listOf(simState.name, voiceServiceState.name, dataState.name).any { it in MissingEvidenceNames }
                }
            }

            LocalSimScope.PERMISSION_DENIED, LocalSimScope.UNAVAILABLE -> {
                true
            }

            else -> {
                false
            }
        }
    return deviceIncomplete || simIncomplete || transport == LocalTransport.UNKNOWN
}

/** Remove unusable capture times and any SIM facts outside a stable default-data selection. */
fun LocalNetworkContextModel.toSafeLocalNetworkContext(): LocalNetworkContextModel =
    copy(
        capturedAt = capturedAt.takeIf { it in 1..MaxLocalCaptureTime } ?: 0,
        sim = if (sim.scope == LocalSimScope.DEFAULT_DATA) sim else emptyLocalSim(sim.scope),
    )

private const val MaxLocalCaptureTime = 253_402_300_799_999L
private val MissingEvidenceNames = setOf("UNKNOWN", "PERMISSION_DENIED", "UNAVAILABLE")
private val UnreadySimStates =
    setOf(
        LocalSimState.ABSENT,
        LocalSimState.PIN_REQUIRED,
        LocalSimState.PUK_REQUIRED,
        LocalSimState.NETWORK_LOCKED,
        LocalSimState.NOT_READY,
        LocalSimState.PERMANENTLY_DISABLED,
        LocalSimState.CARD_IO_ERROR,
        LocalSimState.CARD_RESTRICTED,
    )
private val UnregisteredServiceStates =
    setOf(
        LocalServiceState.OUT_OF_SERVICE,
        LocalServiceState.EMERGENCY_ONLY,
        LocalServiceState.POWER_OFF,
    )
