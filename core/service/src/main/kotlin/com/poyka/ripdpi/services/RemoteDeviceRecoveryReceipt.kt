package com.poyka.ripdpi.services

import android.net.VpnService
import android.os.SystemClock
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Privacy-safe receipt for an observed VPN service start. It never drives a restart or a probe. */
data class RemoteDeviceRecoveryReceipt(
    val persistenceAvailability: String = UnknownReceiptValue,
    val generation: String = UnknownReceiptValue,
    val startOrigin: String = UnknownReceiptValue,
    val userUnlocked: String = UnknownReceiptValue,
    val alwaysOn: String = UnknownReceiptValue,
    val lockdown: String = UnknownReceiptValue,
    val serviceInstanceChanged: String = UnknownReceiptValue,
    val timeToForegroundService: String = NotObservedReceiptValue,
    val timeToTun: String = NotObservedReceiptValue,
    val timeToFirstFlow: String = NotObservedReceiptValue,
    val postStartDataPlaneOutcome: String = PendingReceiptValue,
)

internal fun interface RemoteDeviceRecoveryReceiptSource {
    fun snapshot(): RemoteDeviceRecoveryReceipt
}

internal object EmptyRemoteDeviceRecoveryReceiptSource : RemoteDeviceRecoveryReceiptSource {
    override fun snapshot(): RemoteDeviceRecoveryReceipt = RemoteDeviceRecoveryReceipt()
}

@Singleton
internal class RemoteDeviceRecoveryReceiptCollector internal constructor(
    private val elapsedRealtime: () -> Long,
    private val generationFactory: () -> String,
    private val persistence: RemoteDeviceRecoveryReceiptPersistence,
) : RemoteDeviceRecoveryReceiptSource {
    @Inject
    constructor(
        persistence: SharedPreferencesRemoteDeviceRecoveryReceiptPersistence,
    ) : this(SystemClock::elapsedRealtime, { UUID.randomUUID().toString() }, persistence)

    private val lock = Any()
    private val restored = persistence.load()
    private var persistedGenerationHash: String? = restored?.generationHash
    private var lastServiceInstanceHash: String? = restored?.serviceInstanceHash
    private var active: ActiveRecoveryReceipt? = null
    private var latest = restored?.receipt ?: RemoteDeviceRecoveryReceipt()

    fun beginStart(
        action: String?,
        serviceInstanceId: String,
    ): String =
        synchronized(lock) {
            active
                ?.takeIf { state -> state.serviceInstanceId == serviceInstanceId }
                ?.let { state -> return@synchronized state.generation }
            val generation = generationFactory()
            val generationHash = generation.sha256Hex()
            val serviceInstanceHash = serviceInstanceId.sha256Hex()
            val previousServiceInstanceHash = lastServiceInstanceHash
            val nextActive =
                ActiveRecoveryReceipt(
                    generation = generation,
                    generationHash = generationHash,
                    serviceInstanceId = serviceInstanceId,
                    serviceInstanceHash = serviceInstanceHash,
                    startedAtElapsedMs = elapsedRealtime(),
                )
            val nextReceipt =
                RemoteDeviceRecoveryReceipt(
                    generation = PresentReceiptValue,
                    startOrigin = classifyRecoveryStartOrigin(action),
                    serviceInstanceChanged =
                        when {
                            previousServiceInstanceHash == null -> UnknownReceiptValue
                            previousServiceInstanceHash == serviceInstanceHash -> DisabledReceiptValue
                            else -> EnabledReceiptValue
                        },
                )
            if (
                !persist(
                    expectedGenerationHash = persistedGenerationHash,
                    activeState = nextActive,
                    receipt = nextReceipt,
                )
            ) {
                restoreLatestAndDeactivate()
                return@synchronized generation
            }
            active = nextActive
            latest = nextReceipt
            persistedGenerationHash = generationHash
            lastServiceInstanceHash = serviceInstanceHash
            generation
        }

    fun recordForegroundService(
        generation: String,
        userUnlocked: Boolean?,
        policy: AndroidHardKillSwitchSnapshot,
    ) {
        updateIfCurrent(generation) { state, receipt ->
            receipt.copy(
                userUnlocked = userUnlocked.toReceiptValue(),
                alwaysOn = policy.alwaysOn.toReceiptValue(),
                lockdown = policy.lockdown.toReceiptValue(),
                timeToForegroundService = elapsedBucket(state.startedAtElapsedMs, elapsedRealtime()),
            )
        }
    }

    fun recordTunReady(generation: String) {
        updateIfCurrent(generation) { state, receipt ->
            state.baselineTunnelStats = TunnelStats()
            if (receipt.timeToTun != NotObservedReceiptValue) {
                receipt
            } else {
                receipt.copy(timeToTun = elapsedBucket(state.startedAtElapsedMs, elapsedRealtime()))
            }
        }
    }

    fun recordUserUnlocked(generation: String) {
        updateIfCurrent(generation) { _, receipt ->
            receipt.copy(userUnlocked = EnabledReceiptValue)
        }
    }

    fun recordTunnelTelemetry(
        generation: String,
        telemetry: NativeRuntimeSnapshot,
    ) {
        val stats = telemetry.tunnelStats
        updateIfCurrent(generation) { state, receipt ->
            val baseline = state.baselineTunnelStats
            val outbound = stats.txPackets > baseline.txPackets || stats.txBytes > baseline.txBytes
            val inbound = stats.rxPackets > baseline.rxPackets || stats.rxBytes > baseline.rxBytes
            val firstFlow =
                if ((outbound || inbound) && receipt.timeToFirstFlow == NotObservedReceiptValue) {
                    elapsedBucket(state.startedAtElapsedMs, elapsedRealtime())
                } else {
                    receipt.timeToFirstFlow
                }
            receipt.copy(
                timeToFirstFlow = firstFlow,
                postStartDataPlaneOutcome =
                    mergeDataPlaneOutcome(
                        previous = receipt.postStartDataPlaneOutcome,
                        outbound = outbound,
                        inbound = inbound,
                    ),
            )
        }
    }

    fun cancelServiceInstance(serviceInstanceId: String) {
        synchronized(lock) {
            val state = active?.takeIf { it.serviceInstanceId == serviceInstanceId } ?: return
            if (latest.postStartDataPlaneOutcome in ObservedDataPlaneOutcomes) {
                active = null
                return
            }
            val nextReceipt = latest.copy(postStartDataPlaneOutcome = "cancelled")
            if (persist(state.generationHash, state, nextReceipt)) {
                latest = nextReceipt
            } else {
                restoreLatestAndDeactivate()
            }
            active = null
        }
    }

    override fun snapshot(): RemoteDeviceRecoveryReceipt =
        synchronized(lock) {
            latest.copy(persistenceAvailability = persistence.availability.wireValue)
        }

    private fun updateIfCurrent(
        generation: String,
        update: (ActiveRecoveryReceipt, RemoteDeviceRecoveryReceipt) -> RemoteDeviceRecoveryReceipt,
    ) {
        synchronized(lock) {
            val state = active ?: return
            if (state.generation != generation) return
            val nextReceipt = update(state, latest)
            if (persist(state.generationHash, state, nextReceipt)) {
                latest = nextReceipt
            } else {
                restoreLatestAndDeactivate()
            }
        }
    }

    private fun persist(
        expectedGenerationHash: String?,
        activeState: ActiveRecoveryReceipt,
        receipt: RemoteDeviceRecoveryReceipt,
    ): Boolean =
        persistence.compareAndSet(
            expectedGenerationHash = expectedGenerationHash,
            state =
                PersistedRemoteDeviceRecoveryReceipt(
                    generationHash = activeState.generationHash,
                    serviceInstanceHash = activeState.serviceInstanceHash,
                    receipt = receipt,
                ),
        )

    private fun restoreLatestAndDeactivate() {
        val restored = persistence.load()
        persistedGenerationHash = restored?.generationHash
        lastServiceInstanceHash = restored?.serviceInstanceHash
        latest = restored?.receipt ?: RemoteDeviceRecoveryReceipt()
        active = null
    }
}

private data class ActiveRecoveryReceipt(
    val generation: String,
    val generationHash: String,
    val serviceInstanceId: String,
    val serviceInstanceHash: String,
    val startedAtElapsedMs: Long,
    var baselineTunnelStats: TunnelStats = TunnelStats(),
)

internal fun classifyRecoveryStartOrigin(action: String?): String =
    when (action) {
        null, processDeathRecoveryStartAction -> "process_death"
        VpnService.SERVICE_INTERFACE -> "always_on"
        bootRecoveryStartAction -> "boot"
        packageReplacedRecoveryStartAction -> "package_replaced"
        com.poyka.ripdpi.data.startAction -> "explicit_user"
        diagnosticsStartAction -> "diagnostics_resume"
        transportFailoverRestartAction -> "transport_failover"
        startupFallbackStartAction -> "startup_fallback"
        else -> UnknownReceiptValue
    }

internal fun isRecoveryReceiptStartAction(action: String?): Boolean =
    action == null ||
        action == VpnService.SERVICE_INTERFACE ||
        action == bootRecoveryStartAction ||
        action == packageReplacedRecoveryStartAction ||
        action == processDeathRecoveryStartAction ||
        action == com.poyka.ripdpi.data.startAction ||
        action == diagnosticsStartAction ||
        action == transportFailoverRestartAction ||
        action == startupFallbackStartAction

internal fun RemoteDeviceRecoveryReceipt.privacySafe(): RemoteDeviceRecoveryReceipt =
    copy(
        persistenceAvailability =
            persistenceAvailability.onlyAllowed(RecoveryPersistenceAvailabilityValues),
        generation = generation.onlyAllowed(setOf(PresentReceiptValue, UnknownReceiptValue)),
        startOrigin = startOrigin.onlyAllowed(StartOriginValues),
        userUnlocked = userUnlocked.onlyAllowed(DeviceStateValues),
        alwaysOn = alwaysOn.onlyAllowed(DeviceStateValues),
        lockdown = lockdown.onlyAllowed(DeviceStateValues),
        serviceInstanceChanged = serviceInstanceChanged.onlyAllowed(DeviceStateValues),
        timeToForegroundService = timeToForegroundService.onlyAllowed(ElapsedBucketValues),
        timeToTun = timeToTun.onlyAllowed(ElapsedBucketValues),
        timeToFirstFlow = timeToFirstFlow.onlyAllowed(ElapsedBucketValues),
        postStartDataPlaneOutcome = postStartDataPlaneOutcome.onlyAllowed(DataPlaneOutcomeValues),
    )

private fun String.onlyAllowed(allowed: Set<String>): String = takeIf(allowed::contains) ?: UnknownReceiptValue

private fun String.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun elapsedBucket(
    startedAtElapsedMs: Long,
    observedAtElapsedMs: Long,
): String {
    val elapsed = observedAtElapsedMs - startedAtElapsedMs
    return when {
        elapsed < 0L -> UnknownReceiptValue
        elapsed < OneSecondMs -> "under_1s"
        elapsed < FiveSecondsMs -> "1_to_5s"
        elapsed < TenSecondsMs -> "5_to_10s"
        elapsed < ThirtySecondsMs -> "10_to_30s"
        else -> "30s_or_more"
    }
}

private fun Boolean?.toReceiptValue(): String =
    when (this) {
        true -> EnabledReceiptValue
        false -> DisabledReceiptValue
        null -> UnknownReceiptValue
    }

private fun mergeDataPlaneOutcome(
    previous: String,
    outbound: Boolean,
    inbound: Boolean,
): String =
    when {
        outbound && inbound -> "bidirectional_observed"
        previous in ObservedDataPlaneOutcomes -> previous
        outbound -> "outbound_only"
        inbound -> "inbound_only"
        previous == PendingReceiptValue -> "no_flow_observed"
        else -> previous
    }

private val ObservedDataPlaneOutcomes = setOf("bidirectional_observed", "outbound_only", "inbound_only")
private val RecoveryPersistenceAvailabilityValues =
    RemoteDeviceRecoveryReceiptPersistenceAvailability.entries
        .mapTo(mutableSetOf()) { availability -> availability.wireValue } + UnknownReceiptValue
private val StartOriginValues =
    setOf(
        "process_death",
        "always_on",
        "boot",
        "package_replaced",
        "explicit_user",
        "diagnostics_resume",
        "transport_failover",
        "startup_fallback",
    )
private val DeviceStateValues = setOf("enabled", "disabled", UnknownReceiptValue)
private val ElapsedBucketValues =
    setOf("under_1s", "1_to_5s", "5_to_10s", "10_to_30s", "30s_or_more", NotObservedReceiptValue)
private val DataPlaneOutcomeValues =
    ObservedDataPlaneOutcomes + setOf(PendingReceiptValue, "no_flow_observed", "cancelled")
internal const val UnknownReceiptValue = "unknown"
internal const val NotObservedReceiptValue = "not_observed"
internal const val PendingReceiptValue = "pending"
internal const val PresentReceiptValue = "present"
private const val EnabledReceiptValue = "enabled"
private const val DisabledReceiptValue = "disabled"
private const val OneSecondMs = 1_000L
private const val FiveSecondsMs = 5_000L
private const val TenSecondsMs = 10_000L
private const val ThirtySecondsMs = 30_000L
