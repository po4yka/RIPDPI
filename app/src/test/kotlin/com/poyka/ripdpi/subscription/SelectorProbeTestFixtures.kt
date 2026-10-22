package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun selectorTestScope(group: ProxyGroup) =
    SelectorProbeScope(
        group.members,
        group.failover,
        group.cloudflareMemberIds,
        "opaque-test-network",
        1L,
        TestPhysicalNetworkEvidence().capture(),
        com.poyka.ripdpi.services.CandidateRelayProbeEnvironment(
            false,
            "chrome_stable",
            emptyMap(),
            false,
            false,
        ),
    )

internal fun selectorTestMember(id: String) =
    ProxyProfile.Trojan(
        id = id,
        displayName = id,
        groupId = "g",
        server = "$id.example.com",
        serverPort = 443,
        password = "pw",
    )

internal fun selectorTestGroup(
    members: List<ProxyProfile>,
    intervalSeconds: Int = 10,
    toleranceMs: Int = 50,
) = ProxyGroup(
    id = "g",
    name = "Group",
    type = ProxyGroupType.SUBSCRIPTION,
    order = 0,
    isSelector = true,
    members = members,
    failover =
        SelectorFailover(
            probeUrl = "https://probe",
            intervalSeconds = intervalSeconds,
            toleranceMs = toleranceMs,
        ),
)

internal class ProbeTestSelectionStore : SelectorSelectionStore {
    override fun manualReceipt(groupId: String): com.poyka.ripdpi.data.DurableCommandReceipt? = null

    override fun selectReserved(
        groupId: String,
        profileId: String,
        receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
    ): Boolean = error("Reserved activation is outside this fixture's scenario")

    private val flows = HashMap<String, MutableStateFlow<String?>>()
    private val manual = mutableSetOf<String>()
    private val revisions = mutableMapOf<String, Long>()

    fun set(
        groupId: String,
        profileId: String?,
    ) = write(groupId, profileId, false)

    override fun selectedProfileId(groupId: String): StateFlow<String?> = flowFor(groupId).asStateFlow()

    override fun invalidatePendingSelection(groupId: String) {
        revisions[groupId] = (revisions[groupId] ?: 0L) + 1L
    }

    override fun snapshot(groupId: String): SelectorSelectionSnapshot =
        SelectorSelectionSnapshot(flowFor(groupId).value, groupId in manual, revisions[groupId] ?: 0L)

    override fun selectAutomatically(
        groupId: String,
        expected: SelectorSelectionSnapshot,
        profileId: String,
    ): Boolean {
        if (snapshot(groupId) != expected) return false
        write(groupId, profileId, false)
        return true
    }

    override suspend fun select(
        groupId: String,
        profileId: String,
    ) = write(groupId, profileId, true)

    override fun clearSelection(groupId: String) = write(groupId, null, false)

    private fun write(
        groupId: String,
        profileId: String?,
        isManual: Boolean,
    ) {
        if (isManual) manual.add(groupId) else manual.remove(groupId)
        revisions[groupId] = (revisions[groupId] ?: 0L) + 1L
        flowFor(groupId).value = profileId
    }

    private fun flowFor(groupId: String): MutableStateFlow<String?> = flows.getOrPut(groupId) { MutableStateFlow(null) }
}
