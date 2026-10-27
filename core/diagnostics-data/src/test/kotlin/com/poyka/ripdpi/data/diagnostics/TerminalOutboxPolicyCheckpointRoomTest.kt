package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import kotlinx.coroutines.test.runTest
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
            val marker =
                DiagnosticsDurableStateEntity(
                    key = "runtime_terminal_outbox:usage-deleted-policy",
                    value = "runtime-events",
                    updatedAt = 30L,
                )
            store.beginTerminalOutbox(finishedSession(), marker, policyDependency = null)
            val deletedPolicy =
                RememberedNetworkPolicyEntity(
                    id = 9L,
                    fingerprintHash = "deleted-policy",
                    mode = "vpn",
                    summaryJson = "{}",
                    proxyConfigJson = "{}",
                    source = RememberedNetworkPolicySource.MANUAL_SESSION.encodeStorageValue(),
                    status = RememberedNetworkPolicyStatusValidated,
                    firstObservedAt = 20L,
                    updatedAt = 20L,
                )

            assertTrue(store.checkpointTerminalPolicy(deletedPolicy, marker, marker.copy(value = "session-upsert")))

            assertNull(dao.getRememberedNetworkPolicy("deleted-policy", "vpn"))
        }

    private fun finishedSession() =
        BypassUsageSessionEntity(
            id = "usage-deleted-policy",
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
}
