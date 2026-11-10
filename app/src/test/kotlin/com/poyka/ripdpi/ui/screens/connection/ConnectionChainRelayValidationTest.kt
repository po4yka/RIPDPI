package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionChainRelayValidationTest {
    @Test
    fun `chain validation rejects every empty or missing middle position`() {
        for (middle in listOf("", " \t", "missing")) {
            val errors = validateConfigDraft(chainDraft(middle), relayProfiles = profiles())

            assertEquals("required", errors[ConnectionFieldRelayChain])
        }
    }

    @Test
    fun `chain validation rejects unsupported middle kinds and quic carriers`() {
        for (kind in listOf(RelayKindChainRelay, RelayKindHysteria2, RelayKindTuicV5)) {
            val errors =
                validateConfigDraft(
                    chainDraft("middle"),
                    relayProfiles = profiles() + RelayProfileRecord(id = "middle", kind = kind),
                )

            assertEquals("unsupported", errors[ConnectionFieldRelayChain])
        }
    }

    @Test
    fun `chain validation keeps a complete supported middle selection`() {
        val errors =
            validateConfigDraft(
                chainDraft("middle"),
                relayProfiles = profiles() + RelayProfileRecord(id = "middle", kind = RelayKindVlessReality),
            )

        assertNull(errors[ConnectionFieldRelayChain])
    }

    private fun profiles(): List<RelayProfileRecord> =
        listOf(
            RelayProfileRecord(id = "entry", kind = RelayKindVlessReality),
            RelayProfileRecord(id = "exit", kind = RelayKindVlessReality),
        )

    private fun chainDraft(middle: String): ConnectionDraft =
        ConnectionDraft(
            relayEnabled = true,
            relayKind = RelayKindChainRelay,
            relayChainEntryProfileId = "entry",
            relayChainMiddleProfileIds = persistentListOf(middle),
            relayChainExitProfileId = "exit",
        )
}
