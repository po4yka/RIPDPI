package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigChainRelayUiStateTest {
    @Test
    fun `chain relay profile options expose heterogeneous hop trust metadata`() {
        val options =
            buildRelayProfileOptions(
                records =
                    listOf(
                        RelayProfileRecord(
                            id = "chain",
                            kind = RelayKindChainRelay,
                        ),
                        RelayProfileRecord(
                            id = "entry",
                            kind = RelayKindVlessReality,
                            jurisdiction = "RU",
                            operatorName = "Entry Transit",
                        ),
                        RelayProfileRecord(
                            id = "exit",
                            kind = RelayKindMasque,
                            jurisdiction = "NL",
                            operatorName = "Exit Transit",
                        ),
                    ),
                chainProfileId = "chain",
            )

        assertEquals(listOf("entry", "exit"), options.map { it.id })
        assertEquals("VLESS + Reality", options[0].kindLabel)
        assertEquals("MASQUE", options[1].kindLabel)
        assertEquals("RU", options[0].jurisdiction)
        assertEquals("Exit Transit", options[1].operatorName)
    }

    @Test
    fun `chain relay trust warning detects selected profiles that share a jurisdiction`() {
        val profiles =
            listOf(
                RelayProfileRecord(
                    id = "entry",
                    kind = RelayKindVlessReality,
                    jurisdiction = "RU",
                    operatorName = "Entry Transit",
                ),
                RelayProfileRecord(
                    id = "exit",
                    kind = RelayKindMasque,
                    jurisdiction = "ru",
                    operatorName = "Exit Transit",
                ),
            )
        val draft =
            ConfigDraft(
                relayKind = RelayKindChainRelay,
                relayChainEntryProfileId = "entry",
                relayChainExitProfileId = "exit",
            )

        val warning = resolveRelayChainTrustWarning(draft, buildRelayProfileOptions(profiles, chainProfileId = "chain"))

        assertEquals("RU", warning?.sharedJurisdiction)
        assertEquals(null, warning?.sharedOperatorName)
    }

    @Test
    fun `chain relay validation rejects missing same or unsupported profile selections`() {
        val profiles =
            listOf(
                RelayProfileRecord(id = "entry", kind = RelayKindVlessReality),
                RelayProfileRecord(id = "chain-hop", kind = RelayKindChainRelay),
                RelayProfileRecord(id = "off-hop", kind = RelayKindOff),
            )

        assertEquals(
            "required",
            validateConfigDraft(
                ConfigDraft(
                    relayEnabled = true,
                    relayKind = RelayKindChainRelay,
                    relayChainEntryProfileId = "",
                    relayChainExitProfileId = "entry",
                ),
                relayProfiles = profiles,
            )[ConfigFieldRelayChain],
        )
        assertEquals(
            "same_hop",
            validateConfigDraft(
                ConfigDraft(
                    relayEnabled = true,
                    relayKind = RelayKindChainRelay,
                    relayChainEntryProfileId = "entry",
                    relayChainExitProfileId = "entry",
                ),
                relayProfiles = profiles,
            )[ConfigFieldRelayChain],
        )
        assertEquals(
            "required",
            validateConfigDraft(
                ConfigDraft(
                    relayEnabled = true,
                    relayKind = RelayKindChainRelay,
                    relayChainEntryProfileId = "entry",
                    relayChainExitProfileId = "exit",
                ),
                relayProfiles = emptyList(),
            )[ConfigFieldRelayChain],
        )
        assertEquals(
            "unsupported",
            validateConfigDraft(
                ConfigDraft(
                    relayEnabled = true,
                    relayKind = RelayKindChainRelay,
                    relayChainEntryProfileId = "entry",
                    relayChainExitProfileId = "chain-hop",
                ),
                relayProfiles = profiles,
            )[ConfigFieldRelayChain],
        )
    }

    @Test
    fun `chain relay validation rejects quic exit profiles`() {
        val profiles =
            listOf(
                RelayProfileRecord(id = "entry", kind = RelayKindVlessReality),
                RelayProfileRecord(id = "quic-exit", kind = RelayKindHysteria2),
            )

        assertEquals(
            "unsupported_exit",
            validateConfigDraft(
                ConfigDraft(
                    relayEnabled = true,
                    relayKind = RelayKindChainRelay,
                    relayChainEntryProfileId = "entry",
                    relayChainExitProfileId = "quic-exit",
                ),
                relayProfiles = profiles,
            )[ConfigFieldRelayChain],
        )
    }

    @Test
    fun `chain relay hop status is projected from native telemetry`() {
        val status =
            buildRelayChainHopStatus(
                NativeRuntimeSnapshot(
                    source = "relay",
                    chainEntryState = "connected",
                    chainEntryLatencyMs = 24,
                    chainExitState = "connecting",
                    chainExitLatencyMs = 61,
                ),
            )

        assertEquals("Connected", status.entry.statusLabel)
        assertEquals("24 ms", status.entry.latencyLabel)
        assertEquals("Connecting", status.exit.statusLabel)
        assertEquals("61 ms", status.exit.latencyLabel)
    }
}
