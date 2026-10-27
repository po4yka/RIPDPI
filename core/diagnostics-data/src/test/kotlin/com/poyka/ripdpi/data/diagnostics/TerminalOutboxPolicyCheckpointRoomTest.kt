package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusObserved
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalOutboxPolicyCheckpointRoomTest : DiagnosticsRoomStoreTestBase() {
    @Test
    fun `terminal outbox does not restore a remembered policy deleted before the checkpoint`() =
        runTest {
            val store = RoomDiagnosticsTerminalOutboxStore(db, dao)
            val marker = outboxMarker("usage-deleted-policy")
            store.beginTerminalOutbox(finishedSession("usage-deleted-policy"), marker, policyDependency = null)
            val deletedPolicy =
                policy(fingerprintHash = "deleted-policy", status = RememberedNetworkPolicyStatusValidated)

            assertTrue(
                store.checkpointTerminalPolicy(
                    policyId = deletedPolicy.id,
                    update = { deletedPolicy },
                    expectedMarker = marker,
                    replacementMarker = marker.copy(value = "session-upsert"),
                ),
            )

            assertNull(dao.getRememberedNetworkPolicy("deleted-policy", "vpn"))
        }

    @Test
    fun `concurrent store and terminal outbox updates keep every failure`() =
        runTest {
            val outbox = RoomDiagnosticsTerminalOutboxStore(db, dao)
            val recordStore = RoomRememberedNetworkPolicyRecordStore(dao, clock)
            val policyStore = DefaultRememberedNetworkPolicyStore(recordStore, clock)
            val snapshot = policy(fingerprintHash = "shared-policy", status = RememberedNetworkPolicyStatusObserved)
            dao.upsertRememberedNetworkPolicy(snapshot)
            val markers =
                (0 until RacingWriters).map { index ->
                    val sessionId = "usage-race-$index"
                    outboxMarker(sessionId).also { marker ->
                        outbox.beginTerminalOutbox(finishedSession(sessionId), marker, policyDependency = null)
                    }
                }

            withContext(Dispatchers.Default) {
                markers.forEach { marker ->
                    launch { policyStore.recordFailure(policy = snapshot, failedAt = 40L, allowSuppression = false) }
                    launch {
                        outbox.checkpointTerminalPolicy(
                            policyId = snapshot.id,
                            update = { current -> current.copy(failureCount = current.failureCount + 1) },
                            expectedMarker = marker,
                            replacementMarker = marker.copy(value = "session-upsert"),
                        )
                    }
                }
            }

            assertEquals(2 * RacingWriters, dao.getRememberedNetworkPolicyById(snapshot.id)?.failureCount)
        }

    private fun outboxMarker(sessionId: String) =
        DiagnosticsDurableStateEntity(
            key = "runtime_terminal_outbox:$sessionId",
            value = "runtime-events",
            updatedAt = 30L,
        )

    private fun policy(
        fingerprintHash: String,
        status: String,
    ) = RememberedNetworkPolicyEntity(
        id = 9L,
        fingerprintHash = fingerprintHash,
        mode = "vpn",
        summaryJson = "{}",
        proxyConfigJson = "{}",
        source = RememberedNetworkPolicySource.MANUAL_SESSION.encodeStorageValue(),
        status = status,
        firstObservedAt = 20L,
        updatedAt = 20L,
    )

    private fun finishedSession(id: String) =
        BypassUsageSessionEntity(
            id = id,
            startedAt = 20L,
            finishedAt = 30L,
            updatedAt = 30L,
            serviceMode = "VPN",
            approachProfileId = "profile",
            approachProfileName = "Profile",
            strategyId = "strategy",
            strategyLabel = "Strategy",
            strategyJson = "{}",
            networkType = "wifi",
            txBytes = 1L,
            rxBytes = 2L,
            totalErrors = 0L,
            routeChanges = 0L,
            restartCount = 0,
            endedReason = null,
        )

    private companion object {
        const val RacingWriters = 40
    }
}
