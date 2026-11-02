package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TcpPayloadReservesTest {
    @Test
    fun `UDP payload failure tests TCP before another UDP reserve and honors recovery threshold`() =
        runTest {
            val active = ProxyProfile.Hysteria2("udp", "udp", "g", "relay.example", 443, "fixture")
            val tcp =
                ProxyProfile.Vless(
                    "tcp",
                    "tcp",
                    "g",
                    "relay.example",
                    443,
                    "55555555-5555-5555-5555-555555555555",
                    "relay.example",
                    xhttpPath = "/stream",
                    xhttpMode = "stream-up",
                )
            val group =
                selectorTestGroup(listOf(active, active.copy(id = "udp-reserve"), tcp))
                    .copy(failover = SelectorFailover("unused", 60, 0, policy()))
            val store = ProbeTestSelectionStore().apply { select("g", "udp") }
            var now = 0L
            val measured = mutableListOf<String>()
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { member, target ->
                        assertEquals(MemberProbeTarget.Payload(policy()), target)
                        measured += member.id
                        if (member.id == "tcp") MemberProbeResult(20L) else null
                    },
                    SelectorProbeScopeProvider { selectorTestScope(group) },
                    { now },
                )

            prober.runProbePass(group, "unused", 0)
            assertEquals(listOf("udp", "tcp"), measured)
            assertEquals("udp", store.selectedProfileId("g").value)
            now = 60_000
            prober.runProbePass(group, "unused", 0)
            assertEquals("tcp", store.selectedProfileId("g").value)
        }
}
