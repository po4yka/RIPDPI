package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootPolicyAuthorityTest {
    @Test fun `unknown legacy marker never proves desired running`() {
        val authority = testPauseAuthority()
        assertFalse(authority.allowsRecovery(authority.reference(), Mode.VPN))
    }

    @Test fun `checked stop suppresses stale running marker and wrong mode recovery`() {
        val authority = testPauseAuthority()
        authority.supersede(RuntimeUserCommand.Start(Mode.VPN))
        assertTrue(authority.allowsRecovery(authority.reference(), Mode.VPN))
        assertFalse(authority.allowsRecovery(authority.reference(), Mode.Proxy))
        authority.supersede(RuntimeUserCommand.Stop)
        assertFalse(authority.allowsRecovery(authority.reference(), Mode.VPN))
    }

    @Test fun `standing real boot policy may replace a prior stop with a distinct receipt`() {
        val authority = testPauseAuthority()
        authority.supersede(RuntimeUserCommand.Stop)
        val receipt = authority.authorizeBootPolicyStart(Mode.Proxy, authority.snapshotAuthority())
        assertNotNull(receipt)
        assertTrue(authority.allowsRecovery(receipt!!.reference, Mode.Proxy))
    }

    @Test fun `stop or pause between capture and policy authorization preserves newer intent`() {
        val authority = testPauseAuthority()
        val old = authority.snapshotAuthority()
        authority.supersede(RuntimeUserCommand.Stop)
        assertNull(authority.authorizeBootPolicyStart(Mode.VPN, old))
        val stopped = authority.snapshotAuthority()
        authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
        assertNull(authority.authorizeBootPolicyStart(Mode.VPN, stopped))
        assertNotNull(authority.snapshot())
    }

    @Test fun `pending pause and its acknowledged transition cannot reuse a captured policy snapshot`() {
        val authority = testPauseAuthority()
        val pause = authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
        authority.transition(pause, PausePhase.Paused, null)
        val captured = authority.snapshotAuthority()
        assertNull(authority.authorizeBootPolicyStart(Mode.VPN, captured))
        assertTrue(authority.claimResume(pause, true))
        assertTrue(authority.acknowledgeResume(pause, Mode.VPN))
        assertNull(authority.authorizeBootPolicyStart(Mode.VPN, captured))
        assertTrue(authority.allowsRecovery(authority.reference(), Mode.VPN))
    }

    @Test fun `policy receipt becomes stale after a newer stop`() {
        val authority = testPauseAuthority()
        val receipt = authority.authorizeBootPolicyStart(Mode.VPN, authority.snapshotAuthority())!!
        authority.supersede(RuntimeUserCommand.Stop)
        assertFalse(authority.allowsRecovery(receipt.reference, Mode.VPN))
    }
}
