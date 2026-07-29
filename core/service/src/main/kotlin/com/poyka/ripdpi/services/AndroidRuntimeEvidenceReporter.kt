package com.poyka.ripdpi.services

import android.app.ForegroundServiceStartNotAllowedException
import android.app.InvalidForegroundServiceTypeException
import android.app.MissingForegroundServiceTypeException
import android.content.ComponentCallbacks2
import android.os.Build
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.DeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.DeviceRuntimeForegroundCallKind
import com.poyka.ripdpi.data.DeviceRuntimeForegroundOutcome
import com.poyka.ripdpi.data.DeviceRuntimeForegroundServiceType
import com.poyka.ripdpi.data.DeviceRuntimeKillSwitchStatus
import com.poyka.ripdpi.data.DeviceRuntimeLifecyclePhase
import com.poyka.ripdpi.data.DeviceRuntimeMemoryPressure
import com.poyka.ripdpi.data.DeviceRuntimeValue
import com.poyka.ripdpi.data.Mode
import javax.inject.Inject
import javax.inject.Singleton

internal fun interface AndroidRuntimeEvidenceClock {
    fun now(): Long
}

@Singleton
internal class SystemAndroidRuntimeEvidenceClock
    @Inject
    constructor() : AndroidRuntimeEvidenceClock {
        override fun now(): Long = System.currentTimeMillis()
    }

@Singleton
class AndroidRuntimeEvidenceReporter
    @Inject
    internal constructor(
        private val store: DeviceRuntimeEvidenceStore,
        private val clock: AndroidRuntimeEvidenceClock,
    ) {
        private val policyLock = Any()
        private var lastVpnPolicy: VpnPolicyProjection? = null

        fun recordLifecycle(
            mode: Mode,
            phase: DeviceRuntimeLifecyclePhase,
        ) {
            store.record(DeviceRuntimeEvidence.ServiceLifecycle(mode, phase, clock.now()))
        }

        @Suppress("TooGenericExceptionCaught")
        fun runForegroundCall(
            mode: Mode,
            kind: DeviceRuntimeForegroundCallKind,
            serviceType: DeviceRuntimeForegroundServiceType,
            call: () -> Unit,
        ) {
            try {
                call()
                recordForegroundCall(mode, kind, DeviceRuntimeForegroundOutcome.Returned, serviceType)
            } catch (error: RuntimeException) {
                recordForegroundCall(mode, kind, classifyForegroundFailure(error), serviceType)
                throw error
            }
        }

        fun recordVpnPolicy(snapshot: AndroidHardKillSwitchSnapshot) {
            val projection = snapshot.toEvidenceProjection()
            synchronized(policyLock) {
                if (projection == lastVpnPolicy) return
                lastVpnPolicy = projection
            }
            store.record(
                DeviceRuntimeEvidence.VpnPolicy(
                    alwaysOn = projection.alwaysOn,
                    lockdown = projection.lockdown,
                    killSwitch = projection.killSwitch,
                    observedAtMillis = clock.now(),
                ),
            )
        }

        fun recordMemoryTrim(level: Int) {
            store.record(
                DeviceRuntimeEvidence.MemoryTrim(
                    pressure = classifyMemoryPressure(level),
                    observedAtMillis = clock.now(),
                ),
            )
        }

        private fun recordForegroundCall(
            mode: Mode,
            kind: DeviceRuntimeForegroundCallKind,
            outcome: DeviceRuntimeForegroundOutcome,
            serviceType: DeviceRuntimeForegroundServiceType,
        ) {
            store.record(
                DeviceRuntimeEvidence.ForegroundCall(
                    mode = mode,
                    kind = kind,
                    outcome = outcome,
                    observedAtMillis = clock.now(),
                    serviceType = serviceType,
                ),
            )
        }
    }

internal fun classifyForegroundFailure(error: RuntimeException): DeviceRuntimeForegroundOutcome =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException -> {
            DeviceRuntimeForegroundOutcome.StartNotAllowed
        }

        error is SecurityException -> {
            DeviceRuntimeForegroundOutcome.SecurityRejected
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (error is InvalidForegroundServiceTypeException || error is MissingForegroundServiceTypeException) -> {
            DeviceRuntimeForegroundOutcome.InvalidType
        }

        else -> {
            DeviceRuntimeForegroundOutcome.OtherFailure
        }
    }

@Suppress("DEPRECATION")
internal fun classifyMemoryPressure(level: Int): DeviceRuntimeMemoryPressure =
    when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> DeviceRuntimeMemoryPressure.UiHidden

        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> DeviceRuntimeMemoryPressure.Background

        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        -> DeviceRuntimeMemoryPressure.Moderate

        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        -> DeviceRuntimeMemoryPressure.Critical

        else -> DeviceRuntimeMemoryPressure.Unknown
    }

private data class VpnPolicyProjection(
    val alwaysOn: DeviceRuntimeValue,
    val lockdown: DeviceRuntimeValue,
    val killSwitch: DeviceRuntimeKillSwitchStatus,
)

private fun AndroidHardKillSwitchSnapshot.toEvidenceProjection(): VpnPolicyProjection =
    VpnPolicyProjection(
        alwaysOn = alwaysOn.toRuntimeValue(),
        lockdown = lockdown.toRuntimeValue(),
        killSwitch =
            when (status) {
                AndroidHardKillSwitchStatus.ENABLED -> DeviceRuntimeKillSwitchStatus.Enabled
                AndroidHardKillSwitchStatus.NOT_ENABLED -> DeviceRuntimeKillSwitchStatus.NotEnabled
                AndroidHardKillSwitchStatus.UNKNOWN -> DeviceRuntimeKillSwitchStatus.Unknown
            },
    )

private fun Boolean?.toRuntimeValue(): DeviceRuntimeValue =
    when (this) {
        true -> DeviceRuntimeValue.Enabled
        false -> DeviceRuntimeValue.Disabled
        null -> DeviceRuntimeValue.Unknown
    }
