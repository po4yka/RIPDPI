package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.EnumMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface ServiceRuntimeHandle {
    val runtimeId: String
    val mode: Mode
    val activeConnectionPolicy: StateFlow<ActiveConnectionPolicy?>

    suspend fun reloadConnectionPolicy(
        intent: RuntimePolicyReloadIntent,
        isCurrent: suspend () -> Boolean,
    ): Boolean

    val diagnosticsInPathRouteLease: DiagnosticsInPathRouteLease?
        get() = null
}

abstract class ServiceRuntimeSession
    protected constructor(
        final override val mode: Mode,
        final override val runtimeId: String = UUID.randomUUID().toString(),
    ) : ServiceRuntimeHandle {
        internal var reloadPolicy: suspend (suspend () -> Boolean) -> Boolean = { false }

        final override suspend fun reloadConnectionPolicy(
            intent: RuntimePolicyReloadIntent,
            isCurrent: suspend () -> Boolean,
        ): Boolean =
            when (intent) {
                RuntimePolicyReloadIntent.Automatic -> {
                    reloadPolicy(isCurrent)
                }

                is RuntimePolicyReloadIntent.Explicit -> {
                    kotlinx.coroutines.withContext(
                        RuntimeCommandStartAuthority(
                            com.poyka.ripdpi.data.RuntimeAppliedIntent
                                .Activation(intent.receipt),
                        ),
                    ) { reloadPolicy(isCurrent) }
                }
            }

        private var autolearnActivationGeneration: Long = 0L
        private var configurationRevision: Long = 0L
        internal lateinit var originalAppliedIntent: com.poyka.ripdpi.data.RuntimeAppliedIntent
            private set

        internal fun captureOriginalAppliedIntent(original: com.poyka.ripdpi.data.RuntimeAppliedIntent) {
            check(!::originalAppliedIntent.isInitialized) { "Runtime intent was already captured" }
            originalAppliedIntent = original
        }

        internal var pauseResumeIntent: com.poyka.ripdpi.data.PauseIntent? = null
        internal var lastPositiveReceipt: com.poyka.ripdpi.data.RuntimeActivationReceipt? = null
        internal var lastPositiveAppliedIdentity: com.poyka.ripdpi.data.RuntimeAppliedUseIdentity? = null

        internal fun nextAppliedIntent(): com.poyka.ripdpi.data.RuntimeAppliedIntent =
            lastPositiveAppliedIdentity?.let {
                com.poyka.ripdpi.data.RuntimeAppliedIntent
                    .Continuation(checkNotNull(lastPositiveReceipt), it)
            } ?: originalAppliedIntent

        internal var configurationAttempt: com.poyka.ripdpi.data.RuntimeConfigurationAttempt? = null
        internal var effectiveConfigurationIdentity: RuntimeConfigurationIdentity? = null
        internal var effectiveProviderIdentity: RuntimeConfigurationIdentity? = null

        internal fun nextConfigurationRevision(): Long =
            Math.addExact(configurationRevision, 1).also {
                configurationRevision =
                    it
            }

        private val activeConnectionPolicyState = MutableStateFlow<ActiveConnectionPolicy?>(null)
        var localNetworkDependent: Boolean = false

        final override val activeConnectionPolicy: StateFlow<ActiveConnectionPolicy?> =
            activeConnectionPolicyState
                .asStateFlow()

        val currentActiveConnectionPolicy: ActiveConnectionPolicy?
            get() = activeConnectionPolicy.value

        fun updateActiveConnectionPolicy(policy: ActiveConnectionPolicy?) {
            activeConnectionPolicyState.value = policy
        }

        fun clearActiveConnectionPolicy() {
            activeConnectionPolicyState.value = null
        }

        internal fun nextAutolearnActivationGeneration(): Long {
            autolearnActivationGeneration += 1L
            return autolearnActivationGeneration
        }
    }

class VpnRuntimeSession(
    runtimeId: String = UUID.randomUUID().toString(),
) : ServiceRuntimeSession(
        mode = Mode.VPN,
        runtimeId = runtimeId,
    ),
    HandoverAwareSession {
    override var pendingNetworkHandoverClass: String? = null
    override var networkHandoverState: String? = null
    override var lastSuccessfulHandoverFingerprintHash: String? = null
    override var lastSuccessfulHandoverAt: Long = 0L
    var currentDns: ActiveDnsSettings? = null
    var currentDnsSignature: String? = null
    var currentDestinationRoutingDigest: String? = null
    var currentNetworkScopeKey: String? = null
    internal val encryptedDnsFailoverState = VpnEncryptedDnsFailoverState()

    @Volatile
    private var inPathRouteLease: DiagnosticsInPathRouteLease? = null

    override val diagnosticsInPathRouteLease: DiagnosticsInPathRouteLease?
        get() = inPathRouteLease

    internal fun publishInPathLease(
        endpoint: LocalProxyEndpoint,
        routeGeneration: Long,
    ) {
        val username = checkNotNull(endpoint.username) { "VPN diagnostics route requires proxy authentication" }
        val password = checkNotNull(endpoint.password) { "VPN diagnostics route requires proxy authentication" }
        inPathRouteLease =
            DiagnosticsInPathRouteLease(
                runtimeId = runtimeId,
                routeGeneration = routeGeneration,
                issuedRevision = null,
                host = endpoint.host,
                port = endpoint.port,
                credentials = DiagnosticsProxyCredentials(username, password),
            )
    }

    internal fun revokeInPathLease() {
        inPathRouteLease = null
    }
}

internal fun VpnRuntimeSession.recordDestinationPolicy(resolution: ConnectionPolicyResolution) {
    currentDestinationRoutingDigest = resolution.destinationRoutingDigest
}

internal fun VpnRuntimeSession.clearDestinationPolicy() {
    currentDestinationRoutingDigest = null
}

class ProxyRuntimeSession(
    runtimeId: String = UUID.randomUUID().toString(),
) : ServiceRuntimeSession(
        mode = Mode.Proxy,
        runtimeId = runtimeId,
    ),
    HandoverAwareSession {
    override var pendingNetworkHandoverClass: String? = null
    override var networkHandoverState: String? = null
    override var lastSuccessfulHandoverFingerprintHash: String? = null
    override var lastSuccessfulHandoverAt: Long = 0L
    var currentDestinationRoutingDigest: String? = null
}

interface ServiceRuntimeRegistry {
    val runtimes: StateFlow<Map<Mode, ServiceRuntimeHandle>>

    fun register(handle: ServiceRuntimeHandle)

    fun unregister(
        mode: Mode,
        runtimeId: String,
    )

    fun current(mode: Mode): ServiceRuntimeHandle? = runtimes.value[mode]
}

@Singleton
class DefaultServiceRuntimeRegistry
    @Inject
    constructor() : ServiceRuntimeRegistry {
        private val state = MutableStateFlow<Map<Mode, ServiceRuntimeHandle>>(emptyMap())

        override val runtimes: StateFlow<Map<Mode, ServiceRuntimeHandle>> = state.asStateFlow()

        override fun register(handle: ServiceRuntimeHandle) {
            state.update { current ->
                EnumMap<Mode, ServiceRuntimeHandle>(Mode::class.java).apply {
                    putAll(current)
                    put(handle.mode, handle)
                }
            }
        }

        override fun unregister(
            mode: Mode,
            runtimeId: String,
        ) {
            state.update { current ->
                if (current[mode]?.runtimeId != runtimeId) {
                    return@update current
                }
                EnumMap<Mode, ServiceRuntimeHandle>(Mode::class.java).apply {
                    putAll(current)
                    remove(mode)
                }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceRuntimeRegistryModule {
    @Binds
    @Singleton
    abstract fun bindServiceRuntimeRegistry(registry: DefaultServiceRuntimeRegistry): ServiceRuntimeRegistry
}

sealed interface RuntimePolicyReloadIntent {
    data object Automatic : RuntimePolicyReloadIntent

    data class Explicit(
        val receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt,
    ) : RuntimePolicyReloadIntent
}
