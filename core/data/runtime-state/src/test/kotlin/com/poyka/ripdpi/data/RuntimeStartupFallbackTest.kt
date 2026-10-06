package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStartupFallbackTest {
    @Test fun `failed ordinary start transfers original intent to a new typed fallback command`() {
        val f = Fixture()
        val start = f.authority.reserveStart(Mode.VPN)
        val captured = f.authority.snapshotAuthority()
        assertTrue(f.authority.claimActivation(start, identity))
        assertTrue(f.authority.terminateActivation(start, identity))
        val fallback = checkNotNull(f.authority.authorizeStartupFallback(captured))
        assertEquals(start.authority, fallback.authority)
        assertFalse(start.commandId == fallback.commandId)
        assertEquals(RuntimeCommandOrigin.StartupFallback(start.commandId), fallback.origin)
        assertNull(f.authority.activationReceiptFromEnvelope(start.envelope()))
        val next = identity.copy(runtimeId = "fallback")
        assertTrue(f.authority.claimActivation(fallback, next))
        assertTrue(
            f.authority.acknowledgeApplied(
                RuntimeAppliedIntent.Activation(fallback),
                RuntimeAppliedUseReceipt(next, emptyList(), 100, 0, false, "0".repeat(64)),
            ),
        )
        assertEquals(DesiredRuntimeState.Running, f.authority.snapshotAuthority().desired)
    }

    @Test fun `newer stop or pause prevents an older fallback from reserving a command`() {
        val f = Fixture()
        val start = f.authority.reserveStart(Mode.VPN)
        val captured = f.authority.snapshotAuthority()
        assertTrue(f.authority.claimActivation(start, identity))
        assertTrue(f.authority.terminateActivation(start, identity))
        f.authority.reserveStop()
        assertNull(f.authority.authorizeStartupFallback(captured))
        f.authority.begin(Mode.VPN, 300_000, f.authority.snapshotAuthority())
        assertNull(f.authority.authorizeStartupFallback(captured))
    }

    @Test fun `fallback cannot borrow stop reset measured activation or an unfailed start`() {
        val f = Fixture()
        f.authority.reserveStart(Mode.VPN)
        assertNull(f.authority.authorizeStartupFallback(f.authority.snapshotAuthority()))
        f.authority.reserveStop()
        assertNull(f.authority.authorizeStartupFallback(f.authority.snapshotAuthority()))
        f.authority.reserveResetStop()
        assertNull(f.authority.authorizeStartupFallback(f.authority.snapshotAuthority()))
        val profile =
            f.authority.invalidateForMutation(
                ProfileMutationOrigin.ExplicitActivation,
                "profile",
                f.authority.reference(),
            ) as ProfileMutationOutcome.Reserved
        val activation =
            checkNotNull(f.authority.bindProfileActivation(profile.receipt as ProfileActivationReceipt, Mode.VPN))
        val captured = f.authority.snapshotAuthority()
        assertTrue(f.authority.claimActivation(activation, identity))
        assertTrue(f.authority.terminateActivation(activation, identity))
        assertNull(f.authority.authorizeStartupFallback(captured))
    }

    @Test fun `failed fallback persistence leaves the terminated owner intact and retryable`() {
        val f = Fixture()
        val start = f.authority.reserveStart(Mode.VPN)
        val captured = f.authority.snapshotAuthority()
        assertTrue(f.authority.claimActivation(start, identity))
        assertTrue(f.authority.terminateActivation(start, identity))
        val before = f.authority.states.value
        f.disk.fail = true
        assertTrue(runCatching { f.authority.authorizeStartupFallback(captured) }.isFailure)
        assertEquals(before, f.authority.states.value)
        f.disk.fail = false
        assertTrue(f.authority.authorizeStartupFallback(captured) != null)
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
            ).also { it.initializeAfterMigration() }
    }

    private class Disk : PauseAuthorityPersistence {
        var state: PauseAuthorityState? = null
        var fail = false

        override fun read() = state

        override fun commit(state: PauseAuthorityState) {
            check(!fail)
            this.state = state
        }
    }

    private val identity = RuntimeAppliedUseIdentity("ordinary-start", 1, Mode.VPN.preferenceValue)
}
