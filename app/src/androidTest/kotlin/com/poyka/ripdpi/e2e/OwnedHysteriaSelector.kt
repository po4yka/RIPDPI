package com.poyka.ripdpi.e2e

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.util.UUID

/** Owns only a new persisted group; timers, probes, selection CAS and reload remain production code. */
internal class OwnedHysteriaSelector(
    private val groups: ProxyGroupRepository,
    private val selections: SelectorSelectionStore,
    private val active: SelectorActiveGroupStore,
    private val mutations: ProfileMutationRecoveryCoordinator,
    private val stores: ProfileMutationStores,
    private val applied: AppliedRuntimeConfigurationSource,
    manifest: JSONObject,
    fixtureHost: String,
    hysteriaPort: Int,
) : AutoCloseable {
    private val originalActive = active.snapshotActiveGroupId()
    private val originalWarp = runBlocking { stores.warpProfiles.activeProfileId() }
    private val originalAwg = stores.bootSession.activeAwgProfileId()
    private val groupId = "owned-hysteria-${UUID.randomUUID()}"
    private val primaryId = "$groupId-primary"
    private val reserveId = "$groupId-reserve"
    private var created = false
    private val policy =
        PayloadFailoverPolicy(
            schemaVersion = 2,
            selectorTag = "select",
            probes =
                listOf("quality-a.example.test", "quality-b.example.test").map { host ->
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
            "Owned automatic UDP recovery",
            ProxyGroupType.BASIC,
            0,
            true,
            members =
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
                    ProxyProfile.VlessReality(
                        reserveId,
                        "Owned TCP reserve",
                        groupId,
                        fixtureHost,
                        manifest.getInt("tcpPort"),
                        manifest.getString("uuid"),
                        manifest.getString("publicKey"),
                        "ab12",
                        "fixture.test",
                        fingerprint = "firefox",
                    ),
                ),
            failover = SelectorFailover(policy.probes.first().url, policy.intervalSeconds, 0, policy),
        )

    fun prepareStandalonePreflight() = active.clearStandalone()

    fun selectPrimaryBeforeStart() =
        runBlocking {
            check(groups.list().none { it.id == groupId })
            groups.add(group)
            created = true
            selections.select(groupId, primaryId)
        }

    fun awaitPrimary(): AppliedRuntimeConfiguration {
        awaitUntil(timeoutMs = 30_000) { current()?.effectiveSelection?.selectorMemberId == primaryId }
        return requireNotNull(current())
    }

    fun awaitAutomaticReserve(previous: AppliedRuntimeConfiguration) {
        awaitUntil(timeoutMs = 180_000, failureMessage = {
            "Automatic reserve missing: manual=${selections.snapshot(groupId).isManual} applied=${current()}"
        }) {
            val selected = selections.snapshot(groupId)
            val actual = current()
            selected.profileId == reserveId && !selected.isManual && actual != null &&
                actual.effectiveSelection.selectorGroupId == groupId &&
                actual.effectiveSelection.selectorMemberId == reserveId && actual.revision > previous.revision
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
        try {
            stores.warpProfiles.setActiveProfileId(originalWarp)
        } finally {
            try {
                stores.bootSession.setActiveAwgProfileId(originalAwg)
            } finally {
                active.restoreActiveGroup(originalActive)
            }
        }
    }
}
