package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityPersistence
import com.poyka.ripdpi.data.PauseAuthorityState
import com.poyka.ripdpi.data.PauseClock
import com.poyka.ripdpi.data.PauseClockReading
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAppliedUseIdentity
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.RuntimeIntentLinearizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAppliedCompositeStoreTest {
    @Test
    fun `actual ACK duplicate stays idempotent across store recreation`() {
        val fixture = Fixture()
        val attempt = fixture.attempt(1)
        fixture.store.begin(attempt, fixture.identity)
        val config = fixture.applied(attempt)
        assertTrue(fixture.store.acknowledge(attempt, config, null))
        val commits = fixture.disk.commits
        val committed = checkNotNull(fixture.disk.state)
        assertTrue(fixture.store.acknowledge(attempt, config, null))
        assertEquals(commits, fixture.disk.commits)
        assertEquals(committed, fixture.disk.state)
        val restored = AppliedRuntimeConfigurationStore(testRuntimeAppliedReceiptConsumer(fixture.authority))
        restored.begin(attempt, fixture.identity)
        assertTrue(restored.acknowledge(attempt, config, null))
        assertEquals(commits, fixture.disk.commits)
        assertEquals(committed, fixture.disk.state)
        assertEquals(
            1L,
            fixture.disk.state
                ?.profileUtility
                ?.lastSequence,
        )
        assertFalse(restored.acknowledge(attempt, config.copy(appliedAt = 201), null))
        assertFalse(
            restored.acknowledge(
                attempt,
                config.copy(requestedSelection = config.requestedSelection.copy(profileId = "other")),
                null,
            ),
        )
        assertFalse(
            restored.acknowledge(
                attempt,
                config.copy(effectiveSelection = config.effectiveSelection.copy(profileId = "other")),
                null,
            ),
        )
        val recreatedAgain = AppliedRuntimeConfigurationStore(testRuntimeAppliedReceiptConsumer(fixture.authority))
        recreatedAgain.begin(attempt, fixture.identity)
        assertTrue(
            runCatching {
                recreatedAgain.acknowledge(attempt, config.copy(dns = config.dns.copy(providerId = "changed")), null)
            }.isFailure,
        )
        assertEquals(commits, fixture.disk.commits)
    }

    @Test
    fun `newer Start prevents old actual ACK rather than adopting current authority`() {
        val fixture = Fixture()
        val attempt = fixture.attempt(1)
        fixture.store.begin(attempt, fixture.identity)
        fixture.authority.reserveStart(Mode.Proxy)
        val before = fixture.disk.state
        assertFalse(fixture.store.acknowledge(attempt, fixture.applied(attempt), null))
        assertEquals(before, fixture.disk.state)
        assertTrue(fixture.store.applications.value[Mode.Proxy] is RuntimeConfigurationApplication.Applying)
        fixture.authority.reserveStop()
        val cancelled = fixture.disk.state
        assertFalse(fixture.store.acknowledge(attempt, fixture.applied(attempt), null))
        assertEquals(cancelled, fixture.disk.state)
    }

    @Test
    fun `failed provisioned commit preserves original identity for an exact successful retry`() {
        val fixture = Fixture()
        val attempt = fixture.attempt(1)
        fixture.store.begin(attempt, fixture.identity)
        val provisioned = RuntimeConfigurationIdentityFactory().capture(listOf("automatic"), emptyList())
        val before = fixture.disk.state
        fixture.disk.fail = true
        assertTrue(
            runCatching {
                fixture.store.acknowledgeProvisioned(
                    attempt,
                    fixture.applied(attempt),
                    fixture.identity,
                    provisioned,
                    null,
                )
            }.isFailure,
        )
        assertEquals(before, fixture.disk.state)
        assertTrue(fixture.store.applications.value[Mode.Proxy] is RuntimeConfigurationApplication.Applying)
        fixture.disk.fail = false
        assertTrue(
            fixture.store.acknowledgeProvisioned(
                attempt,
                fixture.applied(attempt),
                fixture.identity,
                provisioned,
                null,
            ),
        )
        assertEquals(
            1L,
            fixture.disk.state
                ?.profileUtility
                ?.lastSequence,
        )
    }

    @Test
    fun `provisioned duplicate requires the same committed configuration and identity`() {
        val fixture = Fixture()
        val attempt = fixture.attempt(1)
        fixture.store.begin(attempt, fixture.identity)
        val provisioned = RuntimeConfigurationIdentityFactory().capture(listOf("automatic"), emptyList())
        val config = fixture.applied(attempt)
        assertTrue(fixture.store.acknowledgeProvisioned(attempt, config, fixture.identity, provisioned, null))
        val commits = fixture.disk.commits
        val committed = checkNotNull(fixture.disk.state)
        assertTrue(fixture.store.acknowledgeProvisioned(attempt, config, fixture.identity, provisioned, null))
        assertEquals(committed, fixture.disk.state)
        assertFalse(
            fixture.store.acknowledgeProvisioned(
                attempt,
                config.copy(appliedAt = 201),
                fixture.identity,
                provisioned,
                null,
            ),
        )
        assertFalse(fixture.store.acknowledgeProvisioned(attempt, config, fixture.identity, fixture.identity, null))
        assertFalse(fixture.store.acknowledgeProvisioned(attempt, config, provisioned, provisioned, null))
        assertEquals(commits, fixture.disk.commits)
        assertEquals(1L, checkNotNull(fixture.disk.state).profileUtility.lastSequence)
    }

    @Test
    fun `DNS refresh remains a mandatory durable ACK without inventing another recent use`() {
        val fixture = Fixture()
        val first = fixture.attempt(1)
        fixture.store.begin(first, fixture.identity)
        assertTrue(fixture.store.acknowledge(first, fixture.applied(first), null))
        val before = checkNotNull(fixture.disk.state).profileUtility
        val dns = fixture.continuation(first, RuntimeConfigurationApplyReason.DnsRefresh)
        fixture.store.begin(dns, fixture.identity)
        assertTrue(fixture.store.acknowledge(dns, fixture.applied(dns), null))
        assertEquals(
            before.recents,
            fixture.disk.state
                ?.profileUtility
                ?.recents,
        )
        assertEquals(
            before.lastSequence,
            fixture.disk.state
                ?.profileUtility
                ?.lastSequence,
        )
        assertEquals(
            2,
            fixture.disk.state
                ?.profileUtility
                ?.acknowledged
                ?.size,
        )
    }

    private class Fixture {
        val disk = Disk()
        val authority =
            PauseIntentAuthority(
                disk,
                object : PauseClock {
                    override fun read() = PauseClockReading(1_800_000_000_000, 10_000, 7)
                },
                RuntimeIntentLinearizer(),
            )
        val selection = RuntimeConfigurationSelection("native", relayKind = "shadowsocks", profileId = "actual")
        val identity = RuntimeConfigurationIdentityFactory().capture(listOf("captured"), emptyList())
        val store = AppliedRuntimeConfigurationStore(testRuntimeAppliedReceiptConsumer(authority))

        init {
            authority.initializeAfterMigration()
            authority.profileUtility.replaceCatalog(setOf(ProfileUtilityReference.NativeRelay("actual")))
        }

        fun attempt(revision: Long) =
            RuntimeConfigurationAttempt(
                "runtime",
                revision,
                Mode.Proxy,
                selection,
                RuntimeConfigurationApplyReason.InitialStart,
                RuntimeAppliedIntent.Activation(authority.reserveStart(Mode.Proxy)),
                checkNotNull(authority.states.value).profileUtility.catalogGeneration,
            )

        fun continuation(
            previous: RuntimeConfigurationAttempt,
            reason: RuntimeConfigurationApplyReason,
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

        fun applied(attempt: RuntimeConfigurationAttempt) =
            AppliedRuntimeConfiguration(
                attempt.runtimeId,
                attempt.revision,
                200,
                attempt.mode,
                selection,
                selection,
                RuntimeConfigurationDns("plain", "system"),
                RuntimeConfigurationStrategy(false),
                attempt.reason,
            )
    }

    private class Disk : PauseAuthorityPersistence {
        var state: PauseAuthorityState? = null
        var commits = 0
        var fail = false

        override fun read() = state

        override fun commit(state: PauseAuthorityState) {
            check(!fail)
            this.state = state
            commits++
        }
    }
}
