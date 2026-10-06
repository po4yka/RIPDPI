package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Whitelisted runtime metadata. Secret-bearing configuration identities stay in service ownership. */
@Serializable
data class RuntimeConfigurationSelection(
    val provider: String,
    val transport: String? = null,
    val relayKind: String? = null,
    val profileId: String? = null,
    val selectorGroupId: String? = null,
    val selectorMemberId: String? = null,
)

@Serializable
data class RuntimeConfigurationDns(
    val mode: String,
    val providerId: String,
    val protocol: String? = null,
)

@Serializable
data class RuntimeConfigurationStrategy(
    val enabled: Boolean,
    val packId: String? = null,
    val family: String? = null,
    val custom: Boolean = false,
)

enum class RuntimeConfigurationApplyReason {
    InitialStart,
    UserReconnect,
    NetworkHandover,
    PolicyRefresh,
    SelectorReload,
    DnsRefresh,
    DnsFailover,
    TransportFailover,
}

enum class RuntimeConfigurationApplyFailure {
    PermissionRequired,
    Lockdown,
    RuntimeRejected,
    TimedOut,
    Superseded,
    Cancelled,
    Unknown,
}

data class RuntimeConfigurationAttempt(
    val runtimeId: String,
    val revision: Long,
    val mode: Mode,
    val requestedSelection: RuntimeConfigurationSelection,
    val reason: RuntimeConfigurationApplyReason,
    val originalIntent: RuntimeAppliedIntent,
    val catalogGeneration: Long,
) {
    override fun toString(): String = "RuntimeConfigurationAttempt(mode=$mode, revision=$revision, reason=$reason)"
}

data class AppliedRuntimeConfiguration(
    val runtimeId: String,
    val revision: Long,
    val appliedAt: Long,
    val mode: Mode,
    val requestedSelection: RuntimeConfigurationSelection,
    val effectiveSelection: RuntimeConfigurationSelection,
    val dns: RuntimeConfigurationDns,
    val strategy: RuntimeConfigurationStrategy,
    val reason: RuntimeConfigurationApplyReason,
) {
    override fun toString(): String = "AppliedRuntimeConfiguration(mode=$mode, revision=$revision, reason=$reason)"
}

sealed interface RuntimeConfigurationApplication {
    data object Unknown : RuntimeConfigurationApplication

    data class Applying(
        val attempt: RuntimeConfigurationAttempt,
        val previous: AppliedRuntimeConfiguration?,
    ) : RuntimeConfigurationApplication

    data class Applied(
        val configuration: AppliedRuntimeConfiguration,
    ) : RuntimeConfigurationApplication

    data class Failed(
        val attempt: RuntimeConfigurationAttempt,
        val previous: AppliedRuntimeConfiguration?,
        val failure: RuntimeConfigurationApplyFailure,
    ) : RuntimeConfigurationApplication
}

enum class RuntimeConfigurationPendingStatus {
    Unknown,
    InSync,
    SavedChangesPending,
}

interface AppliedRuntimeConfigurationSource {
    val applications: StateFlow<Map<Mode, RuntimeConfigurationApplication>>
    val pendingChanges: StateFlow<Map<Mode, RuntimeConfigurationPendingStatus>>
}
