package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyFailure
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationPendingStatus
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppliedRuntimeConfigurationStoreTest {
    private val factory = RuntimeConfigurationIdentityFactory()
    private val selection =
        RuntimeConfigurationSelection("native", selectorGroupId = "group", selectorMemberId = "member")

    @Test
    fun `provisioning receipt preserves concurrent edits and user revert`() =
        runTest {
            val store =
                AppliedRuntimeConfigurationStore(
                    PauseAppliedReceiptConsumer(
                        com.poyka.ripdpi.data
                            .testPauseAuthority(),
                    ),
                )
            val next = attempt("runtime", 1)
            val captured = identity("old-credential", "captured-dns")
            val provisioned = identity("automatic-credential", "captured-dns")
            store.begin(next, captured)
            store.observeSaved(Mode.VPN, provisioned)
            assertTrue(store.acknowledgeProvisioned(next, applied(next), captured, provisioned))
            assertEquals(RuntimeConfigurationPendingStatus.InSync, store.pendingChanges.value[Mode.VPN])
            store.observeSaved(Mode.VPN, captured)
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
            val replacement = next.copy(revision = 2)
            store.begin(replacement, captured)
            store.observeSaved(Mode.VPN, identity("concurrent-user-credential", "newer-dns"))
            assertTrue(store.acknowledgeProvisioned(replacement, applied(replacement), captured, provisioned))
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
            val cancelled = next.copy(revision = 3)
            store.begin(cancelled, captured)
            store.fail(cancelled, RuntimeConfigurationApplyFailure.RuntimeRejected)
            assertFalse(store.acknowledgeProvisioned(cancelled, applied(cancelled), captured, provisioned))
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
        }

    @Test
    fun `process starts unknown and old runtime or revision cannot acknowledge a replacement`() =
        runTest {
            val store =
                AppliedRuntimeConfigurationStore(
                    PauseAppliedReceiptConsumer(
                        com.poyka.ripdpi.data
                            .testPauseAuthority(),
                    ),
                )
            assertEquals(RuntimeConfigurationApplication.Unknown, store.applications.value[Mode.VPN])
            val old = attempt("old-runtime", 1)
            val newer = attempt("new-runtime", 1)
            assertTrue(store.begin(old, identity("old")))
            assertTrue(store.begin(newer, identity("new")))
            assertFalse(store.begin(old.copy(revision = 2), identity("stale")))
            assertFalse(store.acknowledge(old, applied(old)))
            assertFalse(store.acknowledge(newer, applied(newer).copy(runtimeId = "different")))
            assertFalse(store.acknowledge(newer, applied(newer).copy(revision = 2)))
            assertFalse(store.acknowledge(newer, applied(newer).copy(mode = Mode.Proxy)))
            assertTrue(store.acknowledge(newer, applied(newer)))
            assertFalse(store.acknowledge(newer, applied(newer)))
            assertEquals(RuntimeConfigurationApplication.Applied(applied(newer)), store.applications.value[Mode.VPN])
            val revision = newer.copy(revision = 2)
            assertTrue(store.begin(revision, identity("newer")))
            assertFalse(store.acknowledge(newer, applied(newer)))
            assertTrue(store.acknowledge(revision, applied(revision)))
            assertFalse(store.begin(newer, identity("new")))
        }

    @Test
    fun `failure retains last confirmation without claiming current configuration applied`() =
        runTest {
            val store =
                AppliedRuntimeConfigurationStore(
                    PauseAppliedReceiptConsumer(
                        com.poyka.ripdpi.data
                            .testPauseAuthority(),
                    ),
                )
            val initial = attempt("runtime", 1)
            store.begin(initial, identity("old"))
            store.acknowledge(initial, applied(initial))
            val replacement = initial.copy(revision = 2)
            store.begin(replacement, identity("new"))
            assertTrue(store.fail(replacement, RuntimeConfigurationApplyFailure.RuntimeRejected))
            assertEquals(
                RuntimeConfigurationApplication.Failed(
                    replacement,
                    applied(initial),
                    RuntimeConfigurationApplyFailure.RuntimeRejected,
                ),
                store.applications.value[Mode.VPN],
            )
            store.stopped(Mode.VPN, "runtime")
            assertTrue(store.applications.value[Mode.VPN] is RuntimeConfigurationApplication.Failed)
            assertFalse(store.acknowledge(replacement, applied(replacement)))
            assertFalse(store.begin(replacement.copy(revision = 3), identity("new")))
        }

    @Test
    fun `saved request compares with acknowledged request and DNS patches retain transport changes`() =
        runTest {
            val store =
                AppliedRuntimeConfigurationStore(
                    PauseAppliedReceiptConsumer(
                        com.poyka.ripdpi.data
                            .testPauseAuthority(),
                    ),
                )
            val initial = attempt("runtime", 1)
            val requested = identity("transport", "dns")
            store.observeSaved(Mode.VPN, requested)
            store.begin(initial, requested)
            store.acknowledge(
                initial,
                applied(initial).copy(effectiveSelection = selection.copy(profileId = "automatic-winner")),
            )
            assertEquals(RuntimeConfigurationPendingStatus.InSync, store.pendingChanges.value[Mode.VPN])
            val concurrent = identity("changed-transport", "changed-dns")
            store.observeSaved(Mode.VPN, concurrent)
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
            val dnsAttempt = initial.copy(revision = 2, reason = RuntimeConfigurationApplyReason.DnsRefresh)
            assertTrue(store.beginDns(dnsAttempt, concurrent))
            store.acknowledge(dnsAttempt, applied(dnsAttempt))
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
            store.observeSaved(Mode.VPN, identity("transport", "changed-dns"))
            assertEquals(RuntimeConfigurationPendingStatus.InSync, store.pendingChanges.value[Mode.VPN])
        }

    @Test
    fun `automatic DNS fallback does not acknowledge a saved DNS edit`() =
        runTest {
            val store =
                AppliedRuntimeConfigurationStore(
                    PauseAppliedReceiptConsumer(
                        com.poyka.ripdpi.data
                            .testPauseAuthority(),
                    ),
                )
            val initial = attempt("runtime", 1)
            store.begin(initial, identity("transport", "dns"))
            store.acknowledge(initial, applied(initial))
            store.observeSaved(Mode.VPN, identity("transport", "saved-dns"))
            val fallback = initial.copy(revision = 2, reason = RuntimeConfigurationApplyReason.DnsFailover)
            assertTrue(store.beginDns(fallback, null))
            store.acknowledge(fallback, applied(fallback))
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
        }

    @Test
    fun `caller cancellation after commit preserves confirmed configuration`() =
        runTest {
            val store =
                AppliedRuntimeConfigurationStore(
                    PauseAppliedReceiptConsumer(
                        com.poyka.ripdpi.data
                            .testPauseAuthority(),
                    ),
                )
            val next = attempt("cancelled-caller", 1)
            val receipt = applied(next)
            val caller =
                launch {
                    assertTrue(store.begin(next, identity("transport")))
                    assertTrue(store.acknowledge(next, receipt))
                    kotlinx.coroutines.awaitCancellation()
                }
            runCurrent()
            caller.cancel()
            runCurrent()
            assertEquals(RuntimeConfigurationApplication.Applied(receipt), store.applications.value[Mode.VPN])
        }

    private fun identity(
        transport: String,
        dns: String = "dns",
    ) = factory.capture(listOf(transport), listOf(dns))

    private fun attempt(
        runtime: String,
        revision: Long,
    ) = RuntimeConfigurationAttempt(
        runtime,
        revision,
        Mode.VPN,
        selection,
        RuntimeConfigurationApplyReason.InitialStart,
    )

    private fun applied(attempt: RuntimeConfigurationAttempt) =
        AppliedRuntimeConfiguration(
            attempt.runtimeId,
            attempt.revision,
            1_000L,
            attempt.mode,
            attempt.requestedSelection,
            selection,
            RuntimeConfigurationDns("plain", "system"),
            RuntimeConfigurationStrategy(false),
            attempt.reason,
        )
}
