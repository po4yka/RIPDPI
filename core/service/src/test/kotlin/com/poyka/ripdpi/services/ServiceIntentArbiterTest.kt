package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceIntentArbiterTest {
    @Test
    fun `rejected user start never publishes provisional intent generation`() {
        val authority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val arbiter = ServiceIntentArbiter(authority)
        val accepted = arbiter.userStart({ arbiter.captureExplicitUserIntentGeneration() }, { true })
        arbiter.userStart<ServiceStartResult>(
            action = {
                assertEquals(accepted, arbiter.explicitUserIntentGeneration.value)
                ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.VpnConsentMissing)
            },
            isAccepted = { it is ServiceStartResult.Accepted },
        )
        assertEquals(accepted, arbiter.explicitUserIntentGeneration.value)
        assertEquals(accepted, arbiter.captureExplicitUserIntentGeneration())
    }

    @Test
    fun `doq save lease rejects vpn dispatch until mutation finishes`() {
        val authority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val arbiter = ServiceIntentArbiter(authority)
        val startReceipt = authority.reserveStart(Mode.VPN)
        val lease = checkNotNull(arbiter.tryReserveDoqSave { true })

        assertEquals(
            ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.DnsSettingsUpdatePending),
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(startReceipt) },
        )
        lease.close()
        assertEquals(
            ServiceStartResult.Accepted(startReceipt),
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(startReceipt) },
        )
    }

    @Test
    fun `stale start completion cannot release a newer vpn reservation`() {
        val authority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val arbiter = ServiceIntentArbiter(authority)
        val startReceipt = authority.reserveStart(Mode.VPN)
        arbiter.dispatchVpnStart { ServiceStartResult.Accepted(startReceipt) }
        val oldGeneration = arbiter.captureVpnStartGeneration()
        assertNull(arbiter.tryReserveDoqSave { true })

        arbiter.dispatchVpnStart { ServiceStartResult.Accepted(startReceipt) }
        val newGeneration = arbiter.captureVpnStartGeneration()
        arbiter.completeVpnStart(oldGeneration)
        assertNull(arbiter.tryReserveDoqSave { true })

        arbiter.completeVpnStart(newGeneration)
        assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
    }

    @Test
    fun `newer start completion cannot release an older in-flight vpn start`() {
        val authority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val arbiter = ServiceIntentArbiter(authority)
        val startReceipt = authority.reserveStart(Mode.VPN)
        arbiter.dispatchVpnStart { ServiceStartResult.Accepted(startReceipt) }
        val oldGeneration = arbiter.captureVpnStartGeneration()
        arbiter.dispatchVpnStart { ServiceStartResult.Accepted(startReceipt) }
        val newGeneration = arbiter.captureVpnStartGeneration()

        arbiter.completeVpnStart(newGeneration)
        assertNull(arbiter.tryReserveDoqSave { true })
        arbiter.completeVpnStart(oldGeneration)
        assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
    }

    @Test
    fun `rejected vpn dispatch releases its pending reservation`() {
        val authority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val arbiter = ServiceIntentArbiter(authority)
        arbiter.dispatchVpnStart {
            ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.VpnConsentMissing)
        }

        assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
    }
}
