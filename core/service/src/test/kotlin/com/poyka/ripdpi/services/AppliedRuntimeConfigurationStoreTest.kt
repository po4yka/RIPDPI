package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAppliedUseIdentity
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyFailure
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationPendingStatus
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.testPauseAuthority
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
            val fixture = Fixture()
            val store = fixture.store
            val next = fixture.attempt("runtime", 1)
            val captured = identity("old-credential", "captured-dns")
            val provisioned = identity("automatic-credential", "captured-dns")
            store.begin(next, captured)
            store.observeSaved(Mode.VPN, provisioned)
            assertTrue(store.acknowledgeProvisioned(next, applied(next), captured, provisioned, null))
            assertEquals(RuntimeConfigurationPendingStatus.InSync, store.pendingChanges.value[Mode.VPN])
            store.observeSaved(Mode.VPN, captured)
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
            val replacement = fixture.continuation(next)
            store.begin(replacement, captured)
            store.observeSaved(Mode.VPN, identity("concurrent-user-credential", "newer-dns"))
            assertTrue(store.acknowledgeProvisioned(replacement, applied(replacement), captured, provisioned, null))
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
            val cancelled = fixture.continuation(replacement)
            store.begin(cancelled, captured)
            store.fail(cancelled, RuntimeConfigurationApplyFailure.RuntimeRejected)
            assertFalse(store.acknowledgeProvisioned(cancelled, applied(cancelled), captured, provisioned, null))
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
        }

    @Test
    fun `process starts unknown and old runtime or revision cannot acknowledge a replacement`() =
        runTest {
            val fixture = Fixture()
            val store = fixture.store
            assertEquals(RuntimeConfigurationApplication.Unknown, store.applications.value[Mode.VPN])
            val old = fixture.attempt("old-runtime", 1)
            assertTrue(store.begin(old, identity("old")))
            val newer = fixture.attempt("new-runtime", 1)
            assertTrue(store.begin(newer, identity("new")))
            assertFalse(store.begin(old.copy(revision = 2), identity("stale")))
            assertFalse(store.acknowledge(old, applied(old), null))
            assertFalse(store.acknowledge(newer, applied(newer).copy(runtimeId = "different"), null))
            assertFalse(store.acknowledge(newer, applied(newer).copy(revision = 2), null))
            assertFalse(store.acknowledge(newer, applied(newer).copy(mode = Mode.Proxy), null))
            assertTrue(store.acknowledge(newer, applied(newer), null))
            val beforeDuplicate = checkNotNull(fixture.authority.states.value)
            assertTrue(store.acknowledge(newer, applied(newer), null))
            assertEquals(
                beforeDuplicate.profileUtility.lastSequence,
                checkNotNull(fixture.authority.states.value).profileUtility.lastSequence,
            )
            assertEquals(
                beforeDuplicate.profileUtility,
                fixture.authority.states.value
                    ?.profileUtility,
            )
            assertEquals(
                beforeDuplicate.desiredMode,
                fixture.authority.states.value
                    ?.desiredMode,
            )
            assertEquals(RuntimeConfigurationApplication.Applied(applied(newer)), store.applications.value[Mode.VPN])
            val revision = fixture.continuation(newer)
            assertTrue(store.begin(revision, identity("newer")))
            assertFalse(store.acknowledge(newer, applied(newer), null))
            assertTrue(store.acknowledge(revision, applied(revision), null))
            assertFalse(store.begin(newer, identity("new")))
        }

    @Test
    fun `failure retains last confirmation without claiming current configuration applied`() =
        runTest {
            val fixture = Fixture()
            val store = fixture.store
            val initial = fixture.attempt("runtime", 1)
            store.begin(initial, identity("old"))
            assertTrue(store.acknowledge(initial, applied(initial), null))
            val replacement = fixture.continuation(initial)
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
            assertFalse(store.acknowledge(replacement, applied(replacement), null))
            assertFalse(store.begin(replacement.copy(revision = 3), identity("new")))
        }

    @Test
    fun `saved request compares with acknowledged request and DNS patches retain transport changes`() =
        runTest {
            val fixture = Fixture()
            val store = fixture.store
            val initial = fixture.attempt("runtime", 1)
            val requested = identity("transport", "dns")
            store.observeSaved(Mode.VPN, requested)
            store.begin(initial, requested)
            assertTrue(
                store.acknowledge(
                    initial,
                    applied(initial).copy(effectiveSelection = selection.copy(profileId = "automatic-winner")),
                    null,
                ),
            )
            assertEquals(RuntimeConfigurationPendingStatus.InSync, store.pendingChanges.value[Mode.VPN])
            val concurrent = identity("changed-transport", "changed-dns")
            store.observeSaved(Mode.VPN, concurrent)
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
            val dnsAttempt = fixture.continuation(initial, RuntimeConfigurationApplyReason.DnsRefresh)
            assertTrue(store.beginDns(dnsAttempt, concurrent))
            assertTrue(store.acknowledge(dnsAttempt, applied(dnsAttempt), null))
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
            store.observeSaved(Mode.VPN, identity("transport", "changed-dns"))
            assertEquals(RuntimeConfigurationPendingStatus.InSync, store.pendingChanges.value[Mode.VPN])
        }

    @Test
    fun `automatic DNS fallback does not acknowledge a saved DNS edit`() =
        runTest {
            val fixture = Fixture()
            val store = fixture.store
            val initial = fixture.attempt("runtime", 1)
            store.begin(initial, identity("transport", "dns"))
            assertTrue(store.acknowledge(initial, applied(initial), null))
            store.observeSaved(Mode.VPN, identity("transport", "saved-dns"))
            val fallback = fixture.continuation(initial, RuntimeConfigurationApplyReason.DnsFailover)
            assertTrue(store.beginDns(fallback, null))
            assertTrue(store.acknowledge(fallback, applied(fallback), null))
            assertEquals(RuntimeConfigurationPendingStatus.SavedChangesPending, store.pendingChanges.value[Mode.VPN])
        }

    @Test
    fun `caller cancellation after commit preserves confirmed configuration`() =
        runTest {
            val fixture = Fixture()
            val store = fixture.store
            val next = fixture.attempt("cancelled-caller", 1)
            val receipt = applied(next)
            val caller =
                launch {
                    assertTrue(store.begin(next, identity("transport")))
                    assertTrue(store.acknowledge(next, receipt, null))
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

    private inner class Fixture {
        val authority = testPauseAuthority()
        val store = AppliedRuntimeConfigurationStore(testRuntimeAppliedReceiptConsumer(authority))

        init {
            authority.profileUtility.replaceCatalog(setOf(ProfileUtilityReference.SelectorMember("group", "member")))
        }

        fun attempt(
            runtime: String,
            revision: Long,
        ) = RuntimeConfigurationAttempt(
            runtime,
            revision,
            Mode.VPN,
            selection,
            RuntimeConfigurationApplyReason.InitialStart,
            originalIntent = RuntimeAppliedIntent.Activation(authority.reserveStart(Mode.VPN)),
            catalogGeneration = checkNotNull(authority.states.value).profileUtility.catalogGeneration,
        )

        fun continuation(
            previous: RuntimeConfigurationAttempt,
            reason: RuntimeConfigurationApplyReason = previous.reason,
        ): RuntimeConfigurationAttempt {
            val predecessor =
                RuntimeAppliedUseIdentity(previous.runtimeId, previous.revision, previous.mode.preferenceValue)
            checkNotNull(authority.acknowledgedAttempt(previous.originalIntent, predecessor))
            return previous.copy(
                revision = previous.revision + 1,
                reason = reason,
                originalIntent = RuntimeAppliedIntent.Continuation(previous.originalIntent.receipt, predecessor),
            )
        }
    }

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
