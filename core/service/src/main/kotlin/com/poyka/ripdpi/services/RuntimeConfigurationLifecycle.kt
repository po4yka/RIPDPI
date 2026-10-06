package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RuntimeConfigurationApplyFailure
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import javax.inject.Inject
import javax.inject.Singleton

/** Owns configuration receipts; publishing a policy or observing Connected never acknowledges it. */
@Singleton
internal class RuntimeConfigurationLifecycle
    @Inject
    constructor(
        private val store: AppliedRuntimeConfigurationStore,
        private val identities: RuntimeConfigurationIdentityFactory,
    ) {
        private val receipts = RuntimeReadyReceiptFactory(identities)

        fun begin(
            session: ServiceRuntimeSession,
            resolution: ConnectionPolicyResolution,
            reason: String,
        ) {
            val requested = resolution.requestedConfiguration
            val attempt =
                RuntimeConfigurationAttempt(
                    runtimeId = session.runtimeId,
                    revision = session.nextConfigurationRevision(),
                    mode = session.mode,
                    requestedSelection = requested.selection,
                    reason = reason.applyReason(),
                )
            session.pauseResumeIntent?.let { store.bindPauseResume(attempt, it) }
            check(store.begin(attempt, requested.identity)) { "Configuration attempt was rejected" }
            session.configurationAttempt = attempt
        }

        fun ready(
            session: ServiceRuntimeSession,
            resolution: ConnectionPolicyResolution,
            evidence: RuntimeStartEvidence,
            observedAt: Long,
            authority: ExplicitUserStartGuard?,
        ) {
            val requested = resolution.requestedConfiguration
            val attempt = checkNotNull(session.configurationAttempt) { "Configuration attempt was not captured" }
            val receipt = receipts.build(session, resolution, evidence, observedAt, attempt)
            val configuration = receipt.configuration
            val provisioned = receipt.provisionedRequestedIdentity
            val acknowledge = {
                if (provisioned == null) {
                    store.acknowledge(attempt, configuration)
                } else {
                    store.acknowledgeProvisioned(attempt, configuration, requested.identity, provisioned)
                }
            }
            if (authority == null) {
                check(acknowledge()) { "Runtime configuration acknowledgment rejected" }
            } else if (!authority.runIfCurrent {
                    check(acknowledge()) { "Runtime configuration acknowledgment rejected" }
                    check(authority.confirmAppliedMode(session.mode)) { "Applied mode acknowledgment was superseded" }
                }
            ) {
                throw kotlinx.coroutines.CancellationException("Start intent superseded before acknowledgment")
            }
        }

        fun beginDns(
            session: VpnRuntimeSession,
            resolution: ConnectionPolicyResolution,
        ) {
            val previous = store.lastConfirmed(session.mode) ?: return
            val automatic = resolution.resolverFallbackReason != null
            val attempt =
                RuntimeConfigurationAttempt(
                    session.runtimeId,
                    session.nextConfigurationRevision(),
                    session.mode,
                    previous.requestedSelection,
                    if (automatic) {
                        RuntimeConfigurationApplyReason.DnsFailover
                    } else {
                        RuntimeConfigurationApplyReason.DnsRefresh
                    },
                )
            if (store.beginDns(attempt, if (automatic) null else resolution.requestedConfiguration.identity)) {
                session.configurationAttempt = attempt
            }
        }

        fun dnsReady(
            session: VpnRuntimeSession,
            observedAt: Long,
            tunnel: RuntimeTunnelReadyEvidence,
        ) {
            val previous = store.lastConfirmed(session.mode) ?: return
            val attempt = session.configurationAttempt ?: return
            val dnsIdentity = identities.capture(emptyList(), tunnel.dnsMaterial())
            session.effectiveConfigurationIdentity = session.effectiveConfigurationIdentity?.withDns(dnsIdentity)
            session.effectiveProviderIdentity = session.effectiveProviderIdentity?.withDns(dnsIdentity)
            store.acknowledge(
                attempt,
                previous.copy(
                    revision = attempt.revision,
                    appliedAt = observedAt,
                    dns = tunnel.resolverDns.runtimeDnsSummary(),
                    reason = attempt.reason,
                ),
            )
        }

        fun failed(
            session: ServiceRuntimeSession?,
            failure: RuntimeConfigurationApplyFailure,
        ) {
            session?.configurationAttempt?.let { store.fail(it, failure) }
        }

        fun serviceStatusChanged(
            session: ServiceRuntimeSession?,
            status: com.poyka.ripdpi.data.ServiceStatus,
        ) {
            when (status) {
                com.poyka.ripdpi.data.ServiceStatus.Failed -> {
                    failed(
                        session,
                        RuntimeConfigurationApplyFailure.RuntimeRejected,
                    )
                }

                com.poyka.ripdpi.data.ServiceStatus.Disconnected -> {
                    stopped(session)
                }

                else -> {
                    Unit
                }
            }
        }

        fun stopped(session: ServiceRuntimeSession?) {
            session?.let { store.stopped(it.mode, it.runtimeId) }
        }
    }

private fun String.applyReason(): RuntimeConfigurationApplyReason =
    when (this) {
        "network_handover" -> RuntimeConfigurationApplyReason.NetworkHandover
        "selector_reload" -> RuntimeConfigurationApplyReason.SelectorReload
        "transport_failover" -> RuntimeConfigurationApplyReason.TransportFailover
        "routing_policy_refresh", "destination_policy_changed" -> RuntimeConfigurationApplyReason.PolicyRefresh
        else -> RuntimeConfigurationApplyReason.InitialStart
    }
