package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.RelayXhttpModeStreamOne
import com.poyka.ripdpi.data.RelayXhttpModeStreamUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigRelayXhttpPoolTest {
    @Test
    fun `editing a bounded profile retains its limit only for stream-up XHTTP`() {
        listOf(RelayKindVless, RelayKindVlessReality).forEach { kind ->
            val source =
                RelayProfileRecord(
                    id = "bounded",
                    kind = kind,
                    vlessTransport = RelayVlessTransportXhttp,
                    xhttpMode = RelayXhttpModeStreamUp,
                    xhttpMaxConnections = 2,
                )
            val draft =
                ConfigDraft(
                    relayKind = kind,
                    relayProfileId = source.id,
                    sourceRelayProfile = source,
                    relayVlessTransport = RelayVlessTransportXhttp,
                    relayXhttpMode = RelayXhttpModeStreamUp,
                )
            assertEquals(2, draft.toRelayProfileRecord(source.id).xhttpMaxConnections)
            assertNull(
                draft
                    .copy(
                        relayXhttpMode = RelayXhttpModeStreamOne,
                    ).toRelayProfileRecord(source.id)
                    .xhttpMaxConnections,
            )
            assertNull(
                draft
                    .copy(relayVlessTransport = RelayVlessTransportRealityTcp)
                    .toRelayProfileRecord(source.id)
                    .xhttpMaxConnections,
            )
            assertNull(draft.toRelayProfileRecord("new-profile").xhttpMaxConnections)
            assertEquals(2, source.xhttpMaxConnections)
        }
    }
}
