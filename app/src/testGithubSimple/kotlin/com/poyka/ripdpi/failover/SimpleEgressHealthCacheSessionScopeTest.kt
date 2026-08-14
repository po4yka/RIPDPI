package com.poyka.ripdpi.failover

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.services.InitialRelayCandidate
import com.poyka.ripdpi.services.InitialRelayTransportClass
import com.poyka.ripdpi.services.RelayHealthScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SimpleEgressHealthCacheSessionScopeTest {
    @Test
    fun `missing persistent network scope quarantines only the current session and positive evidence clears it`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val clock = SessionScopeClock(now = 1_000L)
        val cache = SimpleEgressHealthCache(application, clock)
        val candidate =
            InitialRelayCandidate(
                transportClass = InitialRelayTransportClass.TlsMimicry,
                profileId = "opaque-profile",
                relayKind = "vless_reality",
            )
        val current = RelayHealthScope(persistentNetworkHash = null, sessionGeneration = 7L)
        val other = RelayHealthScope(persistentNetworkHash = null, sessionGeneration = 8L)

        cache.recordConfirmedFailure(current, EgressProof.TcpOnly, candidate.relayKind, candidate.profileId)
        val currentCooling = cache.isCoolingDown(current, EgressProof.TcpOnly, candidate)
        val otherCooling = cache.isCoolingDown(other, EgressProof.TcpOnly, candidate)
        cache.clearConfirmedFailure(current, EgressProof.TcpOnly, candidate.relayKind, candidate.profileId)

        assertEquals(
            listOf(true, false, false),
            listOf(currentCooling, otherCooling, cache.isCoolingDown(current, EgressProof.TcpOnly, candidate)),
        )
    }

    private class SessionScopeClock(
        var now: Long,
    ) : FailoverClock {
        override fun nowMillis(): Long = now
    }
}
