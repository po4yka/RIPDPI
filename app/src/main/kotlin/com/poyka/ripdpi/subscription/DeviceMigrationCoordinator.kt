package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.DiagnosticsNetworkEpoch
import com.poyka.ripdpi.data.ProfileMutationGenerationSource
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.subscription.MigrationDelivery
import com.poyka.ripdpi.data.subscription.MigrationPathTest
import com.poyka.ripdpi.data.subscription.MigrationTarget
import com.poyka.ripdpi.data.subscription.exportReceipt
import com.poyka.ripdpi.data.subscription.migrationFingerprint
import com.poyka.ripdpi.services.ActiveAwgMigrationProbe
import com.poyka.ripdpi.services.AwgMigrationMeasurement
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import com.poyka.ripdpi.services.CandidateRelayMeasurements
import com.poyka.ripdpi.services.CandidateRelayNetworkEpoch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class MigrationCheckResult { Tested, InvalidTarget, ConnectAwgFirst, Changed, Failed }

/** Explicit local work only. No receipt is sent and no profile selection is changed. */
@Singleton
class DeviceMigrationCoordinator internal constructor(
    private val groups: ProxyGroupRepository,
    private val loadAwg: suspend (String) -> AwgActivationRequest?,
    private val withCurrentAwg: suspend (AwgActivationRequest, suspend () -> Boolean) -> Boolean,
    private val readCurrentAwg: suspend (AwgActivationRequest, suspend () -> Boolean) -> Boolean,
    private val mutationGeneration: () -> Long,
    private val relayProbe: suspend (ProxyProfile, String) -> Boolean,
    private val awgProbe: suspend (AwgActivationRequest, String) -> AwgMigrationMeasurement,
    private val epoch: () -> DiagnosticsNetworkEpoch?,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        groups: ProxyGroupRepository,
        awg: AwgProfileRepository,
        relay: CandidateRelayMeasurements,
        activeAwg: ActiveAwgMigrationProbe,
        network: CandidateRelayNetworkEpoch,
        clock: SubscriptionExpiryClock,
        mutations: ProfileMutationGenerationSource,
    ) : this(
        groups,
        { awg.load(it)?.request },
        awg::withCurrentRequest,
        awg::readCurrentRequest,
        { mutations.generation.value },
        { member, url ->
            val mapped = mapRelayProfile(member)
            mapped != null &&
                relay.measure(mapped.profile, mapped.credentials, url) is CandidateRelayMeasurement.Succeeded
        },
        activeAwg::measure,
        network::capture,
        clock::nowMillis,
    )

    suspend fun check(
        groupId: String,
        tag: String,
        url: String,
    ): MigrationCheckResult =
        try {
            checkTarget(groupId, tag, url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MigrationCheckResult.Failed
        }

    private suspend fun checkTarget(
        groupId: String,
        tag: String,
        url: String,
    ): MigrationCheckResult {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return MigrationCheckResult.InvalidTarget
        }
        val captured = capture(groupId, tag)
        val network = captured?.let { epoch() }
        return when {
            captured == null -> MigrationCheckResult.InvalidTarget
            network == null -> MigrationCheckResult.Changed
            else -> measureAndCommit(groupId, captured, network, url)
        }
    }

    private suspend fun measureAndCommit(
        groupId: String,
        captured: CapturedTarget,
        network: DiagnosticsNetworkEpoch,
        url: String,
    ): MigrationCheckResult {
        val result = measure(captured, url)
        currentCoroutineContext().ensureActive()
        if (result != MigrationCheckResult.Tested) return result
        val testedAt = now()
        val commit: suspend () -> Boolean = { commitTest(groupId, captured, network, testedAt) }
        val committed =
            testedAt >= captured.delivery.receivedAtMillis &&
                (captured.awg?.let { withCurrentAwg(it, commit) } ?: commit())
        return if (committed) MigrationCheckResult.Tested else MigrationCheckResult.Changed
    }

    private suspend fun commitTest(
        groupId: String,
        captured: CapturedTarget,
        network: DiagnosticsNetworkEpoch,
        testedAt: Long,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        var committed = false
        groups.updateGroup(groupId) { current ->
            if (!current.matches(captured) || epoch() != network ||
                mutationGeneration() != captured.mutationGeneration
            ) {
                current
            } else {
                committed = true
                current.copy(
                    subscription =
                        current.subscription!!.copy(
                            migration =
                                captured.delivery.copy(
                                    pathTest =
                                        MigrationPathTest(
                                            captured.target.tag,
                                            testedAt,
                                            captured.fullFingerprint,
                                        ),
                                ),
                        ),
                )
            }
        }
        return committed
    }

    suspend fun hasCurrentTest(groupId: String): Boolean {
        val delivery = currentDelivery(groupId)
        val tested = delivery?.pathTest
        val captured = tested?.let { capture(groupId, it.tag) }
        return tested != null && captured != null &&
            captured.delivery == delivery && captured.fullFingerprint == tested.fingerprint &&
            readCurrentTarget(groupId, captured) { true }
    }

    /** Recheck both stores under the AWG row guard before serializing an explicit tested export. */
    suspend fun receipt(groupId: String): String? {
        val delivery = currentDelivery(groupId) ?: return null
        val tested = delivery.pathTest
        val captured = tested?.let { capture(groupId, it.tag) }
        var receipt: String? = null
        if (tested != null && captured?.delivery == delivery && captured.fullFingerprint == tested.fingerprint) {
            readCurrentTarget(groupId, captured) {
                receipt = delivery.exportReceipt()
                true
            }
        }
        return receipt ?: currentDelivery(groupId)?.copy(pathTest = null)?.exportReceipt()
    }

    private suspend fun currentDelivery(groupId: String): MigrationDelivery? =
        groups
            .list()
            .singleOrNull { it.id == groupId }
            ?.subscription
            ?.migration

    private suspend fun readCurrentTarget(
        groupId: String,
        captured: CapturedTarget,
        action: suspend () -> Boolean,
    ): Boolean {
        val checkAndAct: suspend () -> Boolean = {
            groups.list().singleOrNull { it.id == groupId }?.matches(captured) == true && action()
        }
        return captured.awg?.let { readCurrentAwg(it, checkAndAct) } ?: checkAndAct()
    }

    private fun ProxyGroup.matches(captured: CapturedTarget): Boolean =
        subscription?.migration == captured.delivery && (
            captured.relay == null ||
                members.singleOrNull { it.id == captured.target.profileId } == captured.relay
        )

    private suspend fun measure(
        captured: CapturedTarget,
        url: String,
    ): MigrationCheckResult =
        if (captured.awg != null) {
            when (awgProbe(captured.awg, url)) {
                AwgMigrationMeasurement.Succeeded -> MigrationCheckResult.Tested
                AwgMigrationMeasurement.ConnectTargetFirst -> MigrationCheckResult.ConnectAwgFirst
                AwgMigrationMeasurement.EnvironmentChanged -> MigrationCheckResult.Changed
                AwgMigrationMeasurement.Failed -> MigrationCheckResult.Failed
            }
        } else if (relayProbe(checkNotNull(captured.relay), url)) {
            MigrationCheckResult.Tested
        } else {
            MigrationCheckResult.Failed
        }

    private suspend fun capture(
        groupId: String,
        tag: String,
    ): CapturedTarget? {
        val generation = mutationGeneration()
        val group = groups.list().singleOrNull { it.id == groupId } ?: return null
        val delivery = group.subscription?.migration
        val target =
            delivery?.targets?.singleOrNull { it.tag == tag && tag in delivery.metadata.targetTags }
        return if (delivery == null || target == null) {
            null
        } else {
            captureTarget(group, delivery, target, generation)
        }
    }

    private suspend fun captureTarget(
        group: ProxyGroup,
        delivery: MigrationDelivery,
        target: MigrationTarget,
        generation: Long,
    ): CapturedTarget? =
        if (target.amneziaWg) {
            loadAwg(target.profileId)
                ?.takeIf { it.migrationFingerprint() == target.fingerprint }
                ?.let { request ->
                    CapturedTarget(
                        delivery,
                        target,
                        generation,
                        awg = request,
                        fullFingerprint = request.migrationFingerprint(true),
                    )
                }
        } else {
            group.members
                .singleOrNull { it.id == target.profileId }
                ?.takeIf { it.migrationFingerprint() == target.fingerprint }
                ?.let { member ->
                    CapturedTarget(
                        delivery,
                        target,
                        generation,
                        relay = member,
                        fullFingerprint = member.migrationFingerprint(),
                    )
                }
        }

    private data class CapturedTarget(
        val delivery: MigrationDelivery,
        val target: MigrationTarget,
        val mutationGeneration: Long,
        val relay: ProxyProfile? = null,
        val awg: AwgActivationRequest? = null,
        val fullFingerprint: String,
    ) {
        override fun toString(): String = "MigrationCapturedTarget([REDACTED])"
    }
}
