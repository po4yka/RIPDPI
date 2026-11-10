package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionChainRelayPersistenceTest {
    @Test
    fun `new chain profiles preserve each selected middle hop in order`() {
        for (middle in listOf(listOf("middle-a"), listOf("middle-a", "middle-b"))) {
            val draft = chainDraft().copy(relayChainMiddleProfileIds = middle.toImmutableList())

            assertRoute(draft, middle)
        }
    }

    @Test
    fun `editing a source chain replaces reorders and removes its middle hops`() {
        val source =
            RelayProfileRecord(
                id = "chain",
                kind = RelayKindChainRelay,
                chainEntryProfileId = "entry",
                chainMiddleProfileIds = listOf("old-a", "old-b"),
                chainExitProfileId = "exit",
            )
        for (middle in listOf(listOf("new-middle"), listOf("old-b", "old-a"), emptyList())) {
            val draft =
                chainDraft().copy(
                    sourceRelayProfile = source,
                    relayChainMiddleProfileIds = middle.toImmutableList(),
                )

            assertRoute(draft, middle)
        }
    }

    @Test
    fun `another relay kind clears middle hops from profile and settings`() {
        val source = RelayProfileRecord(id = "chain", kind = RelayKindChainRelay, chainMiddleProfileIds = listOf("old"))
        val draft =
            chainDraft().copy(
                relayKind = RelayKindTrojan,
                sourceRelayProfile = source,
                relayChainMiddleProfileIds = persistentListOf("orphan"),
            )

        assertRoute(draft, emptyList())
    }

    private fun assertRoute(
        draft: ConnectionDraft,
        expectedMiddle: List<String>,
    ) {
        val saved = draft.toRelayProfileRecord("chain")
        val settings = AppSettings.newBuilder().applyConfigDraft(draft).build()
        assertEquals(expectedMiddle, saved.chainMiddleProfileIds)
        assertEquals(expectedMiddle, settings.relayChainMiddleProfileIdsList)
        assertEquals(expectedMiddle, settings.toConfigDraft().relayChainMiddleProfileIds)
        if (draft.relayKind == RelayKindChainRelay) {
            val expectedRoute = listOf("entry") + expectedMiddle + "exit"
            assertEquals(
                expectedRoute,
                listOf(saved.chainEntryProfileId) + saved.chainMiddleProfileIds + saved.chainExitProfileId,
            )
            assertEquals(expectedRoute, settings.toConfigDraft().relayChainHopProfileIds())
        }
    }

    private fun chainDraft(): ConnectionDraft =
        ConnectionDraft(
            relayEnabled = true,
            relayKind = RelayKindChainRelay,
            relayProfileId = "chain",
            relayChainEntryProfileId = "entry",
            relayChainExitProfileId = "exit",
        )
}
