package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RuntimeConfigurationApplyFailure

/** Every failed start revokes its own attempt before propagating the original failure. */
internal suspend fun RuntimeConfigurationLifecycle.starting(
    session: ServiceRuntimeSession,
    start: suspend () -> RuntimeStartEvidence,
): RuntimeStartEvidence =
    runCatching { start() }
        .onFailure { failed(session, RuntimeConfigurationApplyFailure.RuntimeRejected) }
        .getOrThrow()

/** The orchestrator invokes this callback only inside its synchronous final authority commit. */
internal fun RuntimeConfigurationLifecycle.completion(
    clock: ServiceClock,
): (
    ServiceRuntimeSession,
    ConnectionPolicyResolution,
    RuntimeStartEvidence,
) -> Unit =
    { session, resolution, evidence ->
        ready(session, resolution, evidence, clock.nowMillis(), authority = null)
    }

/** Dependency ownership for the two startup receipts that precede and complete readiness. */
internal class RuntimeStartReceiptPublishers
    @javax.inject.Inject
    constructor(
        val autolearn: AutolearnActivationReceiptPublisher,
        val configurations: RuntimeConfigurationLifecycle,
    )
