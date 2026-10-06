package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseIntentAuthorityTest {
    @Test fun `failed persistence never publishes or permits teardown`() {
        val disk = Disk()
        val authority =
            PauseIntentAuthority(
                disk,
                Clock(),
                com.poyka.ripdpi.data
                    .RuntimeIntentLinearizer(),
            )
        authority.initializeAfterMigration()
        disk.fail = true
        assertTrue(runCatching { authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority()) }.isFailure)
        assertNull(authority.snapshot())
        assertEquals(0L, authority.reference().generation)
    }

    @Test fun `reconstruction retains exact private intent and generation`() {
        val disk = Disk()
        val clock = Clock()
        val authority =
            PauseIntentAuthority(
                disk,
                clock,
                com.poyka.ripdpi.data
                    .RuntimeIntentLinearizer(),
            )
        authority.initializeAfterMigration()
        val intent = authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority())
        authority.transition(intent, PausePhase.Paused, null)
        val restored =
            PauseIntentAuthority(
                disk,
                clock,
                com.poyka.ripdpi.data
                    .RuntimeIntentLinearizer(),
            )
        assertEquals(intent.copy(phase = PausePhase.Paused), restored.snapshot())
        assertEquals(300_000L, restored.remainingMillis(intent))
    }

    @Test fun `saved and compensation edits preserve pause but explicit mutation invalidates once`() {
        val authority = ready()
        val intent = authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
        ProfileMutationOrigin.entries.filterNot { it.supersedesPause }.forEach {
            authority.invalidateForMutation(it, it.name, authority.reference())
            assertEquals(intent, authority.snapshot())
        }
        authority.invalidateForMutation(ProfileMutationOrigin.ExplicitActivation, "mutation", intent.reference)
        assertNull(authority.snapshot())
        val newer = authority.begin(Mode.VPN, 900_000, authority.snapshotAuthority())
        authority.invalidateForMutation(ProfileMutationOrigin.ExplicitActivation, "mutation", intent.reference)
        assertEquals(newer, authority.snapshot())
    }

    @Test fun `old alarm and receipt cannot resume or clear a newer intent`() {
        val authority = ready()
        val old = authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
        authority.transition(old, PausePhase.Paused, null)
        val receipt = checkNotNull(authority.claimResume(old, true))
        val identity = RuntimeAppliedUseIdentity("old-runtime", 1, Mode.VPN.preferenceValue)
        assertTrue(authority.claimActivation(receipt, identity))
        authority.reserveStop()
        val newer = authority.begin(Mode.Proxy, 900_000, authority.snapshotAuthority())
        assertNull(authority.claimResume(old, true))
        assertFalse(
            authority.acknowledgeApplied(
                RuntimeAppliedIntent.Resume(old, receipt),
                RuntimeAppliedUseReceipt(
                    identity,
                    emptyList(),
                    1,
                    checkNotNull(authority.states.value).profileUtility.catalogGeneration,
                    false,
                    "0".repeat(64),
                ),
            ),
        )
        assertEquals(newer, authority.snapshot())
    }

    @Test fun `deadline does not consume intent before matching positive acknowledgment`() {
        val clock = Clock()
        val authority = ready(clock)
        val intent = authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
        authority.transition(intent, PausePhase.Paused, null)
        assertNull(authority.claimResume(intent, false))
        clock.now = clock.now.copy(elapsedMillis = clock.now.elapsedMillis + 300_000)
        val activation = checkNotNull(authority.claimResume(intent, false))
        assertTrue(
            authority.claimActivation(
                activation,
                RuntimeAppliedUseIdentity("test-actual-resume", 1, Mode.VPN.preferenceValue),
            ),
        )
        assertNotNull(authority.snapshot())
        assertFalse(
            authority.acknowledgeApplied(
                com.poyka.ripdpi.data.RuntimeAppliedIntent
                    .Resume(intent, activation),
                com.poyka.ripdpi.data.RuntimeAppliedUseReceipt(
                    com.poyka.ripdpi.data
                        .RuntimeAppliedUseIdentity("test-actual-resume", 1, Mode.Proxy.preferenceValue),
                    emptyList(),
                    1,
                    checkNotNull(authority.states.value).profileUtility.catalogGeneration,
                    false,
                    "0".repeat(64),
                ),
            ),
        )
        assertTrue(
            authority.acknowledgeApplied(
                com.poyka.ripdpi.data.RuntimeAppliedIntent
                    .Resume(intent, activation),
                com.poyka.ripdpi.data.RuntimeAppliedUseReceipt(
                    com.poyka.ripdpi.data
                        .RuntimeAppliedUseIdentity("test-actual-resume", 1, Mode.VPN.preferenceValue),
                    emptyList(),
                    1,
                    checkNotNull(authority.states.value).profileUtility.catalogGeneration,
                    false,
                    "0".repeat(64),
                ),
            ),
        )
        assertNull(authority.snapshot())
    }

    @Test fun `clock rollback reboot unknown boot and elapsed rollback fail closed`() {
        val clock = Clock()
        val authority = ready(clock)
        val initial = clock.now
        val intent = authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
        listOf(
            initial.copy(wallMillis = initial.wallMillis - 1),
            initial.copy(bootCount = 8),
            initial.copy(bootCount = null),
            initial.copy(elapsedMillis = initial.elapsedMillis - 1),
        ).forEach {
            clock.now = it
            assertNull(authority.remainingMillis(intent))
        }
    }

    @Test fun `automatic recovery defers on reboot but explicit resume succeeds with matching acknowledgment`() {
        val clock = Clock()
        val authority = ready(clock)
        val intent = authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
        authority.transition(intent, PausePhase.Paused, null)
        clock.now = clock.now.copy(bootCount = 8)
        assertNull(authority.claimResume(intent, false))
        assertEquals(PauseFailure.ClockChanged, authority.snapshot()?.failure)
        val activation = checkNotNull(authority.claimResume(intent, true))
        assertTrue(
            authority.claimActivation(
                activation,
                RuntimeAppliedUseIdentity("test-actual-resume", 1, Mode.VPN.preferenceValue),
            ),
        )
        assertNotNull(authority.snapshot())
        assertTrue(
            authority.acknowledgeApplied(
                com.poyka.ripdpi.data.RuntimeAppliedIntent
                    .Resume(intent, activation),
                com.poyka.ripdpi.data.RuntimeAppliedUseReceipt(
                    com.poyka.ripdpi.data
                        .RuntimeAppliedUseIdentity("test-actual-resume", 1, Mode.VPN.preferenceValue),
                    emptyList(),
                    1,
                    checkNotNull(authority.states.value).profileUtility.catalogGeneration,
                    false,
                    "0".repeat(64),
                ),
            ),
        )
        assertNull(authority.snapshot())
    }

    @Test fun `uninitialized authority and overflow cannot publish partial commands`() {
        val disk = Disk()
        val authority =
            PauseIntentAuthority(
                disk,
                Clock(),
                com.poyka.ripdpi.data
                    .RuntimeIntentLinearizer(),
            )
        assertTrue(runCatching { authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority()) }.isFailure)
        disk.value =
            PauseAuthorityState(
                Long.MAX_VALUE,
                null,
                null,
                profileUtility =
                    com.poyka.ripdpi.data.ProfileUtilityState
                        .empty(),
            )
        val overflow =
            PauseIntentAuthority(
                disk,
                Clock(),
                com.poyka.ripdpi.data
                    .RuntimeIntentLinearizer(),
            )
        assertTrue(runCatching { overflow.reserveStop() }.isFailure)
        assertEquals(Long.MAX_VALUE, overflow.reference().generation)
    }

    @Test fun `missing-state Reset writes exactly one stopped generation before any wipe`() {
        val disk = Disk()
        val authority =
            PauseIntentAuthority(
                disk,
                Clock(),
                com.poyka.ripdpi.data
                    .RuntimeIntentLinearizer(),
            )
        val receipt = authority.reserveResetStop()
        assertEquals(1L, receipt.authority.generation)
        assertEquals(DesiredRuntimeState.Stopped, disk.value?.desired)
        assertEquals(1, disk.commits)
    }

    @Test fun `reset stop failure and generation overflow preserve the previous intent`() {
        val disk = Disk()
        val authority =
            PauseIntentAuthority(
                disk,
                Clock(),
                com.poyka.ripdpi.data
                    .RuntimeIntentLinearizer(),
            )
        authority.initializeAfterMigration()
        val pause = authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
        disk.fail = true
        assertTrue(runCatching { authority.reserveResetStop() }.isFailure)
        assertEquals(pause, authority.snapshot())
        disk.fail = false
        disk.value =
            PauseAuthorityState(
                Long.MAX_VALUE,
                null,
                null,
                desired = DesiredRuntimeState.Running,
                profileUtility =
                    com.poyka.ripdpi.data.ProfileUtilityState
                        .empty(),
            )
        val overflow =
            PauseIntentAuthority(
                disk,
                Clock(),
                com.poyka.ripdpi.data
                    .RuntimeIntentLinearizer(),
            )
        assertTrue(runCatching { overflow.reserveResetStop() }.isFailure)
        assertEquals(Long.MAX_VALUE, overflow.reference().generation)
    }

    private fun ready(clock: Clock = Clock()) =
        PauseIntentAuthority(
            Disk(),
            clock,
            com.poyka.ripdpi.data
                .RuntimeIntentLinearizer(),
        ).apply {
            initializeAfterMigration()
        }

    private class Clock : PauseClock {
        var now = PauseClockReading(1_800_000_000_000, 10_000, 7)

        override fun read() = now
    }

    private class Disk : PauseAuthorityPersistence {
        var value: PauseAuthorityState? = null
        var fail = false
        var commits = 0

        override fun read() = value

        override fun commit(state: PauseAuthorityState) {
            check(!fail)
            value = state
            commits++
        }
    }
}
