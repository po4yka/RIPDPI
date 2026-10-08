package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DiagnosticsNetworkEpochProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateRelayNetworkEpochTest {
    @Test fun `each callback changes epoch even when network returns to its previous fingerprint`() {
        val s = CandidatePhysicalNetworkObserver<String>(false)
        val diagnosticsEpoch = DiagnosticsNetworkEpochProvider(s::capture)
        val r = s.beginRegistration()
        ready(s, r, "A")
        val before = diagnosticsEpoch.capture()
        assertNotEquals(null, before)
        assertEquals(before, diagnosticsEpoch.capture())
        ready(s, r, "B")
        s.lost(r, "B")
        ready(s, r, "A")
        assertNotEquals(before, diagnosticsEpoch.capture())
    }

    @Test fun `physical VPN underlay ABA changes scope even if default VPN network is unchanged`() {
        val s = CandidatePhysicalNetworkObserver<String>(false)
        val r = s.beginRegistration()
        ready(s, r, "physical")
        val before = s.capture()
        s.links(r, "physical", "route-B", true)
        s.links(r, "physical", "route-A", true)
        assertNotEquals(before, s.capture())
    }

    @Test fun `failed registration or absent active network cannot supply a probe scope`() {
        val s = CandidatePhysicalNetworkObserver<String>(false)
        val diagnosticsEpoch = DiagnosticsNetworkEpochProvider(s::capture)
        val r = s.beginRegistration()
        assertNull(diagnosticsEpoch.capture())
        s.available(r, "A", true)
        assertNull(diagnosticsEpoch.capture())
        s.endRegistration(r)
        assertNull(diagnosticsEpoch.capture())
    }

    @Test fun `blocked incomplete and old registration callbacks never become Ready`() {
        val s = CandidatePhysicalNetworkObserver<String>(true)
        val old = s.beginRegistration()
        ready(s, old, "A")
        assertNull(s.capture())
        s.blocked(old, "A", true)
        assertNull(s.capture())
        s.blocked(old, "A", false)
        val before = s.capture()
        val next = s.beginRegistration()
        ready(s, old, "A")
        s.blocked(old, "A", false)
        assertNull(s.capture())
        ready(s, next, "A")
        s.blocked(next, "A", false)
        assertNotEquals(before, s.capture())
    }

    @Test fun `unvalidated captive usable path is measurable and flag change invalidates its token`() {
        val s = CandidatePhysicalNetworkObserver<String>(false)
        val r = s.beginRegistration()
        s.available(r, "local", true)
        s.capabilities(r, "local", "unvalidated-captive", true)
        s.links(r, "local", "local-route", true)
        val before = s.capture()
        assertNotEquals(null, before)
        assertEquals(before, s.capture()) // own VPN binder construction has no physical callbacks
        s.capabilities(r, "local", "validated-not-captive", true)
        assertNotEquals(before, s.capture())
        s.capabilities(r, "local", "suspended", false)
        assertNull(s.capture())
    }

    @Test fun `incomplete preferred physical path never falls back to the previous ready path`() {
        val observer = CandidatePhysicalNetworkObserver<String>(false)
        val registration = observer.beginRegistration()
        ready(observer, registration, "old")
        val before = observer.capture()
        observer.available(registration, "new", true)
        assertNull(observer.capture())
        observer.capabilities(registration, "new", "physical-usable", true)
        assertNull(observer.capture())
        observer.links(registration, "new", "route-new", true)
        assertNotEquals(before, observer.capture())
        observer.blocked(registration, "new", true)
        assertNull(observer.capture())
        observer.lost(registration, "new")
        assertNotEquals(null, observer.capture())
    }

    private fun ready(
        s: CandidatePhysicalNetworkObserver<String>,
        r: Long,
        network: String,
    ) {
        s.available(r, network, true)
        s.capabilities(r, network, "physical-usable", true)
        s.links(r, network, "route-A", true)
    }
}
