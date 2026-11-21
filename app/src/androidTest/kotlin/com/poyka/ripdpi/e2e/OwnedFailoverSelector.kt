package com.poyka.ripdpi.e2e

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationRecoveryCoordinator
import com.poyka.ripdpi.data.ProfileMutationStores
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.services.CandidateHttpPayloadProbe
import com.poyka.ripdpi.services.OwnedQualityProbeFactory
import com.poyka.ripdpi.services.RelayProbeEndpoint
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.UUID

/** Owns only a new persisted group; timers, probes, selection CAS and reload remain production code. */
internal class OwnedFailoverSelector(
    private val groups: ProxyGroupRepository,
    private val selections: SelectorSelectionStore,
    private val active: SelectorActiveGroupStore,
    private val mutations: ProfileMutationRecoveryCoordinator,
    private val stores: ProfileMutationStores,
    private val originalSelections: com.poyka.ripdpi.data.ProfileSelections,
    private val authority: com.poyka.ripdpi.data.PauseIntentAuthority,
    private val applied: AppliedRuntimeConfigurationSource,
    manifest: JSONObject,
    createMembers: (String, String, String) -> List<ProxyProfile>,
) : AutoCloseable {
    private val groupId = "owned-failover-${UUID.randomUUID()}"
    private val primaryId = "$groupId-primary"
    private val reserveId = "$groupId-reserve"
    private val members = createMembers(groupId, primaryId, reserveId)
    private var created = false
    private val policy =
        PayloadFailoverPolicy(
            schemaVersion = 2,
            selectorTag = "select",
            probes =
                OwnedQualityProbeFactory.addressControls.map { host ->
                    PayloadProbeControl(
                        "https://$host:${manifest.getInt("qualityPort")}/quality-payload",
                        manifest.getInt("qualityBytes"),
                        manifest.getString("qualitySha256"),
                    )
                },
            intervalSeconds = 30,
            timeoutSeconds = 5,
            failureThreshold = 2,
            recoveryThreshold = 2,
            cooldownSeconds = 30,
            maxBackoffSeconds = 60,
        )
    private val group =
        ProxyGroup(
            groupId,
            "Owned automatic recovery",
            ProxyGroupType.BASIC,
            0,
            true,
            members = members,
            failover = SelectorFailover(policy.probes.first().url, policy.intervalSeconds, 0, policy),
        )

    suspend fun verifyFreshPrimary(probe: com.poyka.ripdpi.subscription.MemberLatencyProbe) {
        val result =
            probe.measure(
                members.first { it.id == primaryId },
                com.poyka.ripdpi.subscription.MemberProbeTarget
                    .Payload(policy),
            )
        org.junit.Assert.assertNotNull(
            "A fresh instance of the primary must verify the full body",
            result?.latencyMillis,
        )
    }

    suspend fun verifyActivePrimary(
        probe: CandidateHttpPayloadProbe,
        lease: DiagnosticsInPathRouteLease,
    ) {
        val result = probe.probePayload(RelayProbeEndpoint(lease.host, lease.port, lease.credentials), policy)
        assertTrue("The active route must work before the fault: ${result.failureStage}", result.succeeded)
    }

    suspend fun prepareStandalonePreflight() = active.clearStandalone()

    fun selectPrimaryBeforeStart() =
        runBlocking {
            pinPrimary()
            resumeAutomatic()
        }

    suspend fun pinPrimary() {
        check(groups.list().none { it.id == groupId })
        groups.add(group)
        created = true
        selections.select(groupId, primaryId)
    }

    suspend fun resumeAutomatic() = selections.resumeAutomatic(groupId)

    fun awaitPrimary(): AppliedRuntimeConfiguration {
        awaitUntil(timeoutMs = 30_000) { current()?.effectiveSelection?.selectorMemberId == primaryId }
        return requireNotNull(current())
    }

    suspend fun awaitAutomaticReserve(previous: AppliedRuntimeConfiguration) {
        kotlinx.coroutines.withTimeout(180_000L) {
            while (true) {
                val selected = selections.snapshot(groupId)
                val actual = current()
                if (selected.profileId == reserveId && !selected.isManual && actual != null &&
                    actual.effectiveSelection.selectorGroupId == groupId &&
                    actual.effectiveSelection.selectorMemberId == reserveId && actual.revision > previous.revision
                ) {
                    break
                }
                kotlinx.coroutines.delay(50L)
            }
        }
        val actual = requireNotNull(current())
        assertEquals("Automatic switch must keep runtime ownership", previous.runtimeId, actual.runtimeId)
        assertEquals(RuntimeConfigurationApplyReason.SelectorReload, actual.reason)
        assertFalse(selections.snapshot(groupId).isManual)
    }

    private fun current() =
        (applied.applications.value[Mode.VPN] as? RuntimeConfigurationApplication.Applied)?.configuration

    override fun close() =
        runBlocking {
            try {
                if (created && groups.list().any { it.id == groupId }) {
                    groups.delete(mutations.captureMutation(ProfileMutationOrigin.ExplicitDeletion), groupId)
                }
            } finally {
                try {
                    if (created) selections.clearSelection(groupId)
                } finally {
                    restorePreviousProfiles()
                }
            }
        }

    private suspend fun restorePreviousProfiles() {
        val originalAwg = originalSelections.awgProfileId
        if (originalAwg != null) {
            check(
                mutations.activateStandaloneAwg(
                    mutations.captureMutation(ProfileMutationOrigin.ExplicitActivation),
                    originalAwg,
                ) is com.poyka.ripdpi.data.ProfileMutationOutcome.Reserved,
            ) {
                "A newer command owns the fixture selection"
            }
        } else {
            val expected = authority.reference()
            check(
                mutations.prepareStandaloneSelectionForStart(
                    object : com.poyka.ripdpi.data.ProfileTransactionGuard {
                        override fun runIfCurrent(action: () -> Unit): Boolean =
                            authority.intentLinearizer.serialize {
                                if (authority.reference() != expected) {
                                    false
                                } else {
                                    action()
                                    true
                                }
                            }
                    },
                ) {},
            ) { "A newer command owns the fixture selection" }
        }
        try {
            stores.warpProfiles.setActiveProfileId(originalSelections.warpProfileId)
        } finally {
            active.restoreActiveGroup(originalSelections.selectorGroupId)
        }
    }
}

internal fun ownedHysteriaMembers(
    manifest: JSONObject,
    fixtureHost: String,
    hysteriaPort: Int,
): (String, String, String) -> List<ProxyProfile> =
    { groupId, primaryId, reserveId ->
        listOf(
            ProxyProfile.Hysteria2(
                primaryId,
                "Owned UDP primary",
                groupId,
                manifest.getString("hysteriaHost"),
                hysteriaPort,
                manifest.getString("hysteriaPassword"),
                "fixture.test",
                manifest.getString("hysteriaSalamander"),
                true,
                manifest.getString("hysteriaCertificatePin"),
            ),
            ownedXrayTcp(manifest, fixtureHost, groupId, reserveId),
        )
    }

internal fun ownedXrayMembers(
    manifest: JSONObject,
    fixtureHost: String,
): (String, String, String) -> List<ProxyProfile> =
    { groupId, primaryId, reserveId ->
        val reserve = ownedXrayTcp(manifest, fixtureHost, groupId, reserveId)
        listOf(
            reserve.copy(
                id = primaryId,
                displayName = "Owned XHTTP primary",
                serverPort = manifest.getInt("xhttpPort"),
                flow = "",
                xhttpPath = "/owned-xhttp",
            ),
            reserve,
        )
    }

private fun ownedXrayTcp(
    manifest: JSONObject,
    fixtureHost: String,
    groupId: String,
    profileId: String,
) = ProxyProfile.VlessReality(
    profileId,
    "Owned TCP reserve",
    groupId,
    fixtureHost,
    manifest.getInt("tcpPort"),
    manifest.getString("uuid"),
    manifest.getString("publicKey"),
    "ab12",
    "fixture.test",
    fingerprint = "firefox",
)
