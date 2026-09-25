package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceIntentArbiterTest {
    @Test
    fun `doq save lease rejects vpn dispatch until mutation finishes`() {
        val arbiter = ServiceIntentArbiter()
        val lease = checkNotNull(arbiter.tryReserveDoqSave { true })

        assertEquals(
            ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.DnsSettingsUpdatePending),
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) },
        )
        lease.close()
        assertEquals(
            ServiceStartResult.Accepted(Mode.VPN),
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) },
        )
    }

    @Test
    fun `stale start completion cannot release a newer vpn reservation`() {
        val arbiter = ServiceIntentArbiter()
        arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
        val oldGeneration = arbiter.captureVpnStartGeneration()
        assertNull(arbiter.tryReserveDoqSave { true })

        arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
        val newGeneration = arbiter.captureVpnStartGeneration()
        arbiter.completeVpnStart(oldGeneration)
        assertNull(arbiter.tryReserveDoqSave { true })

        arbiter.completeVpnStart(newGeneration)
        assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
    }

    @Test
    fun `newer start completion cannot release an older in-flight vpn start`() {
        val arbiter = ServiceIntentArbiter()
        arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
        val oldGeneration = arbiter.captureVpnStartGeneration()
        arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
        val newGeneration = arbiter.captureVpnStartGeneration()

        arbiter.completeVpnStart(newGeneration)
        assertNull(arbiter.tryReserveDoqSave { true })
        arbiter.completeVpnStart(oldGeneration)
        assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
    }

    @Test
    fun `rejected vpn dispatch releases its pending reservation`() {
        val arbiter = ServiceIntentArbiter()
        arbiter.dispatchVpnStart {
            ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.VpnConsentMissing)
        }

        assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
    }
}
