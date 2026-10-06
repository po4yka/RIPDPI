package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCommandAuthorityTest {
    @Test fun `conditional stop rejects changed command phase at same generation`() {
        val f = Fixture()
        val start = f.authority.reserveStart(Mode.Proxy)
        val captured = f.authority.snapshotAuthority()
        assertTrue(f.authority.claimActivation(start, f.identity))
        val claimed = f.authority.snapshotAuthority()
        assertNull(f.authority.reserveStopIfCurrent(captured))
        assertEquals(claimed, f.authority.snapshotAuthority())
        val stop = checkNotNull(f.authority.reserveStopIfCurrent(claimed))
        assertTrue(f.authority.isCurrent(stop))
        assertEquals(DesiredRuntimeState.Stopped, f.authority.snapshotAuthority().desired)
    }

    @Test fun `conditional stop cannot adopt newer same mode Start`() {
        val f = Fixture()
        f.authority.reserveStart(Mode.VPN)
        val captured = f.authority.snapshotAuthority()
        val newer = f.authority.reserveStart(Mode.VPN)
        assertNull(f.authority.reserveStopIfCurrent(captured))
        assertTrue(f.authority.isCurrent(newer))
    }

    @Test fun `explicit activation invalidates paused lease then runs only after exact acknowledgment`() {
        val f = Fixture()
        val pause = f.paused()
        val mutation =
            checkNotNull(
                f.authority.invalidateForMutation(
                    ProfileMutationOrigin.ExplicitActivation,
                    "activate",
                    pause.reference,
                ) as? ProfileMutationOutcome.Reserved,
            )
        val profile = mutation.receipt as ProfileActivationReceipt
        assertNull(f.authority.snapshot())
        assertEquals(
            DesiredRuntimeState.Stopped,
            f.authority.states.value
                ?.desired,
        )
        val activation = checkNotNull(f.authority.bindProfileActivation(profile, Mode.Proxy))
        assertEquals(
            DesiredRuntimeState.Stopped,
            f.authority.states.value
                ?.desired,
        )
        val envelope = activation.envelope()
        assertEquals(activation.commandId, checkNotNull(f.authority.activationReceiptFromEnvelope(envelope)).commandId)
        assertTrue(f.authority.claimActivation(activation, f.identity))
        assertTrue(f.authority.acknowledgeApplied(RuntimeAppliedIntent.Activation(activation), f.receipt()))
        assertEquals(
            DesiredRuntimeState.Running,
            f.authority.states.value
                ?.desired,
        )
        assertEquals(
            1,
            f.authority.states.value
                ?.profileUtility
                ?.recents
                ?.size,
        )
        assertTrue(f.authority.acknowledgeApplied(RuntimeAppliedIntent.Activation(activation), f.receipt()))
        assertEquals(
            1,
            f.authority.states.value
                ?.profileUtility
                ?.recents
                ?.size,
        )
    }

    @Test fun `paused activation claims and records one positive applied use`() {
        val f = Fixture()
        val pause = f.paused()
        val activation = checkNotNull(f.authority.claimResume(pause, true))
        assertTrue(f.authority.claimActivation(activation, f.identity))
        assertTrue(f.authority.acknowledgeApplied(RuntimeAppliedIntent.Resume(pause, activation), f.receipt()))
        assertEquals(
            DesiredRuntimeState.Running,
            f.authority.states.value
                ?.desired,
        )
        assertEquals(
            1,
            f.authority.states.value
                ?.profileUtility
                ?.recents
                ?.size,
        )
        assertTrue(f.authority.acknowledgeApplied(RuntimeAppliedIntent.Resume(pause, activation), f.receipt()))
        assertEquals(
            1,
            f.authority.states.value
                ?.profileUtility
                ?.recents
                ?.size,
        )
    }

    @Test fun `stop reset and forged same generation cannot claim or acknowledge`() {
        val f = Fixture()
        val start = f.authority.reserveStart(Mode.VPN)
        val stop = f.authority.reserveStop()
        val reset = f.authority.reserveResetStop()
        assertFalse(f.authority.claimActivation(start, f.identity))
        assertFalse(f.authority.isCurrent(start))
        assertFalse(f.authority.isCurrent(stop))
        assertTrue(f.authority.isCurrent(reset))
        val forged =
            RuntimeActivationEnvelope(
                reset.authority.generation,
                reset.commandId,
                RuntimeCommandOrigin.UserStart,
                Mode.VPN.preferenceValue,
            )
        assertNull(f.authority.activationReceiptFromEnvelope(forged))
        assertFalse(f.authority.acknowledgeApplied(RuntimeAppliedIntent.Activation(start), f.receipt()))
    }

    @Test fun `terminated activation rejects replay and different command`() {
        val f = Fixture()
        val first = f.authority.reserveStart(Mode.Proxy)
        val second = f.authority.reserveStart(Mode.Proxy)
        assertFalse(f.authority.cancelPendingActivation(first))
        assertTrue(f.authority.cancelPendingActivation(second))
        assertFalse(f.authority.claimActivation(second, f.identity))
        assertFalse(f.authority.acknowledgeApplied(RuntimeAppliedIntent.Activation(second), f.receipt()))
    }

    @Test fun `reconstructed claim terminates once while repeated initialization preserves a live claim`() {
        val f = Fixture()
        val start = f.authority.reserveStart(Mode.Proxy)
        assertTrue(f.authority.claimActivation(start, f.identity))
        f.authority.initializeAfterMigration()
        assertEquals(
            RuntimeActivationPhase.Claimed(f.identity, RuntimeClaimKind.Activation),
            f.authority.states.value
                ?.command
                ?.phase,
        )
        val restarted = PauseIntentAuthority(f.disk, f.clock, RuntimeIntentLinearizer())
        restarted.initializeAfterMigration()
        assertFalse(restarted.claimActivation(start, f.identity))
        restarted.initializeAfterMigration()
        assertEquals(
            RuntimeActivationPhase.Terminated,
            restarted.states.value
                ?.command
                ?.phase,
        )
    }

    @Test fun `failed claim or ack commit preserves disk and flow exactly`() {
        val f = Fixture()
        val start = f.authority.reserveStart(Mode.Proxy)
        val before = f.disk.state
        f.disk.fail =
            true
        assertTrue(runCatching { f.authority.claimActivation(start, f.identity) }.isFailure)
        assertEquals(before, f.disk.state)
        assertEquals(before, f.authority.states.value)
        f.disk.fail = false
        assertTrue(f.authority.claimActivation(start, f.identity))
        val claimed = f.disk.state
        f.disk.fail =
            true
        assertTrue(
            runCatching {
                f.authority.acknowledgeApplied(RuntimeAppliedIntent.Activation(start), f.receipt())
            }.isFailure,
        )
        assertEquals(claimed, f.disk.state)
        assertEquals(claimed, f.authority.states.value)
    }

    @Test fun `newer stop wins resume and overflow does not publish`() {
        val f = Fixture()
        val pause = f.paused()
        f.authority.reserveStop()
        assertNull(f.authority.claimResume(pause, true))
        f.disk.state =
            PauseAuthorityState(
                Long.MAX_VALUE,
                null,
                null,
                desired = DesiredRuntimeState.Stopped,
                profileUtility = ProfileUtilityState.empty(),
            )
        val overflow = PauseIntentAuthority(f.disk, f.clock, RuntimeIntentLinearizer())
        assertTrue(runCatching { overflow.reserveStop() }.isFailure)
        assertEquals(Long.MAX_VALUE, f.disk.state?.generation)
    }

    @Test fun `resume continuation uses original receipt and higher revision`() {
        val f = Fixture()
        val pause = f.paused()
        val activation = checkNotNull(f.authority.claimResume(pause, true))
        assertTrue(f.authority.claimActivation(activation, f.identity))
        assertTrue(f.authority.acknowledgeApplied(RuntimeAppliedIntent.Resume(pause, activation), f.receipt()))
        val next = f.identity.copy(revision = 2)
        assertTrue(f.authority.claimContinuation(activation, f.identity, next))
        val continued = f.receipt().copy(identity = next, recordsUse = false)
        assertTrue(f.authority.acknowledgeApplied(RuntimeAppliedIntent.Continuation(activation, f.identity), continued))
    }

    private class Fixture {
        val disk = Disk()
        val clock = Clock()
        val authority = PauseIntentAuthority(disk, clock, RuntimeIntentLinearizer())
        val reference = ProfileUtilityReference.NativeRelay("actual")
        val identity = RuntimeAppliedUseIdentity("runtime", 1, Mode.Proxy.preferenceValue)

        init {
            authority.initializeAfterMigration()
            authority.profileUtility.replaceCatalog(setOf(reference))
        }

        fun paused() =
            authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority()).also {
                authority.transition(it, PausePhase.Paused, null)
            }

        fun receipt() =
            RuntimeAppliedUseReceipt(
                identity,
                listOf(reference),
                200,
                checkNotNull(authority.states.value).profileUtility.catalogGeneration,
                true,
                "0".repeat(64),
            )
    }

    private class Clock : PauseClock {
        override fun read() = PauseClockReading(1_800_000_000_000, 10_000, 7)
    }

    private class Disk : PauseAuthorityPersistence {
        var state: PauseAuthorityState? = null
        var fail = false

        override fun read() = state

        override fun commit(state: PauseAuthorityState) {
            check(!fail)
            this.state =
                state
        }
    }
}
