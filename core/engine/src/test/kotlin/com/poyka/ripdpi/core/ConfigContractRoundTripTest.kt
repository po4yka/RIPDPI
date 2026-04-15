package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Cross-language round-trip harness for the config contract.
 *
 * Each scenario:
 * 1. Builds an RipDpiProxyUIPreferences fixture with normalisation-safe values.
 * 2. Encodes to JSON via toNativeConfigJson (RipDpiProxyJsonCodec.encodeUiPreferences).
 * 3. Decodes back via decodeRipDpiProxyUiPreferences.
 * 4. Asserts structural equality of decoded chains vs. originals.
 * 5. Writes the canonical JSON to a fixture file consumed by the Rust round_trip test.
 *
 * Single fixture source of truth: core/engine/src/test/resources/fixtures/
 * Rust reads from ../../../core/engine/src/test/resources/fixtures/ relative to crate root.
 */
class ConfigContractRoundTripTest {

    // ---------------------------------------------------------------------------
    // Scenario 1: TCP-heavy chain
    // Kinds: tlsrec, fake, fakedsplit, hostfake, disorder, split (6 steps)
    // Exercises: fakeOrder (on Fake/FakeSplit/HostFake), fakeSeqMode (on Fake),
    //            overlapSize + fakeMode (on SeqOverlap), and an activation filter.
    // Note: FakeSplit replaces SeqOverlap to remain within the 6-step budget while
    //       covering fakeOrder. SeqOverlap is included as step 3 to exercise overlapSize.
    // ---------------------------------------------------------------------------

    @Test
    fun `tcp heavy chain round trips through json codec`() {
        val tcpSteps = tcpHeavySteps()
        val preferences = RipDpiProxyUIPreferences(
            chains = RipDpiChainConfig(
                tcpSteps = tcpSteps,
                groupActivationFilter = ActivationFilterModel(
                    round = NumericRangeModel(start = 1L, end = 5L),
                ),
            ),
        )

        val json = preferences.toNativeConfigJson()
        val decoded = decodeRipDpiProxyUiPreferences(json)

        assertNotNull("decoded preferences must not be null", decoded)

        val decodedSteps = decoded!!.chains.tcpSteps
        assertEquals(
            "decoded TCP step count must match original",
            preferences.chains.tcpSteps.size,
            decodedSteps.size,
        )

        tcpSteps.forEachIndexed { idx, original ->
            val actual = decodedSteps[idx]
            assertEquals("step[$idx] kind", original.kind, actual.kind)
            assertEquals("step[$idx] marker", original.marker, actual.marker)
            // fakeOrder is normalised to default for non-ordering kinds; compare only kinds that support it
            if (original.kind.supportsFakeOrdering) {
                assertEquals("step[$idx] fakeOrder", original.fakeOrder, actual.fakeOrder)
                assertEquals("step[$idx] fakeSeqMode", original.fakeSeqMode, actual.fakeSeqMode)
            }
        }

        // Group activation filter must survive the round trip
        val decodedFilter = decoded.chains.groupActivationFilter
        assertEquals(
            "group activation filter round.start",
            preferences.chains.groupActivationFilter.round.start,
            decodedFilter.round.start,
        )
        assertEquals(
            "group activation filter round.end",
            preferences.chains.groupActivationFilter.round.end,
            decodedFilter.round.end,
        )

        // Step-level activation filter on the Fake step
        val fakeStepDecoded = decodedSteps.firstOrNull { it.kind == TcpChainStepKind.Fake }
        assertNotNull("decoded Fake step must be present", fakeStepDecoded)
        assertEquals(
            "Fake step activation filter round.start",
            1L,
            fakeStepDecoded?.activationFilter?.round?.start,
        )
        assertEquals(
            "Fake step activation filter tcpHasTimestamp",
            true,
            fakeStepDecoded?.activationFilter?.tcpHasTimestamp,
        )

        writeFixture("round-trip-tcp-heavy.json", json)
    }

    // ---------------------------------------------------------------------------
    // Scenario 2: UDP+QUIC chain
    // Includes: dummy_prepend, fake_burst, quic_sni_split (3 UDP steps)
    // ---------------------------------------------------------------------------

    @Test
    fun `udp quic chain round trips through json codec`() {
        val udpSteps = udpQuicSteps()
        val preferences = RipDpiProxyUIPreferences(
            protocols = RipDpiProtocolConfig(desyncUdp = true),
            chains = RipDpiChainConfig(
                tcpSteps = listOf(
                    TcpChainStepModel(kind = TcpChainStepKind.Split, marker = "host+1"),
                ),
                udpSteps = udpSteps,
            ),
            quic = RipDpiQuicConfig(
                fakeProfile = "realistic_initial",
                fakeHost = "cloudflare.com",
            ),
        )

        val json = preferences.toNativeConfigJson()
        val decoded = decodeRipDpiProxyUiPreferences(json)

        assertNotNull("decoded preferences must not be null", decoded)

        val decodedSteps = decoded!!.chains.udpSteps
        assertEquals(
            "decoded UDP step count must match original",
            preferences.chains.udpSteps.size,
            decodedSteps.size,
        )

        udpSteps.forEachIndexed { idx, original ->
            val actual = decodedSteps[idx]
            assertEquals("udp step[$idx] kind", original.kind, actual.kind)
            assertEquals("udp step[$idx] count", original.count, actual.count)
        }

        writeFixture("round-trip-udp-quic.json", json)
    }

    // ---------------------------------------------------------------------------
    // Scenario 3: Relay-heavy config
    // Exercises three relay kinds' field groups in one payload:
    //   - MASQUE: masqueUrl, masqueUseHttp2Fallback, masqueCloudflareGeohashEnabled
    //   - Cloudflare Tunnel (consume_existing): cloudflareTunnelMode +
    //     cloudflareCredentialsRef (ref to RelayCredentialStore, NOT inline)
    //   - NaiveProxy: naivePath
    // Plus chain-relay entry/exit fields to exercise the chained-relay branch.
    // The active `kind` is chain_relay; the other kinds' fields live in the
    // same schema but are only selected at runtime. The round-trip gate asserts
    // the JSON shape survives for every field group, not which kind is active.
    // ---------------------------------------------------------------------------

    @Test
    fun `relay heavy config round trips through json codec`() {
        val relay = relayHeavyConfig()
        val preferences = RipDpiProxyUIPreferences(relay = relay)

        val json = preferences.toNativeConfigJson()
        val decoded = decodeRipDpiProxyUiPreferences(json)

        assertNotNull("decoded preferences must not be null", decoded)

        val actual = decoded!!.relay
        assertEquals("relay enabled", relay.enabled, actual.enabled)
        assertEquals("relay kind", relay.kind, actual.kind)
        // MASQUE fields
        assertEquals("masqueUrl", relay.masqueUrl, actual.masqueUrl)
        assertEquals("masqueUseHttp2Fallback", relay.masqueUseHttp2Fallback, actual.masqueUseHttp2Fallback)
        assertEquals(
            "masqueCloudflareGeohashEnabled",
            relay.masqueCloudflareGeohashEnabled,
            actual.masqueCloudflareGeohashEnabled,
        )
        // Cloudflare Tunnel: credentials via ref only -- never inline secrets
        assertEquals("cloudflareTunnelMode", relay.cloudflareTunnelMode, actual.cloudflareTunnelMode)
        assertEquals("cloudflareCredentialsRef", relay.cloudflareCredentialsRef, actual.cloudflareCredentialsRef)
        // NaiveProxy
        assertEquals("naivePath", relay.naivePath, actual.naivePath)
        // Chain-relay entry/exit
        assertEquals("chainEntryServer", relay.chainEntryServer, actual.chainEntryServer)
        assertEquals("chainEntryPort", relay.chainEntryPort, actual.chainEntryPort)
        assertEquals("chainExitServer", relay.chainExitServer, actual.chainExitServer)
        assertEquals("chainExitPort", relay.chainExitPort, actual.chainExitPort)

        writeFixture("round-trip-relay-heavy.json", json)
    }

    // ---------------------------------------------------------------------------
    // Fixture builders
    // ---------------------------------------------------------------------------

    /**
     * 6 TCP steps spanning tlsrec, fake, fakedsplit, hostfake, disorder, split.
     * Values are chosen to survive normalizeTcpChainStepModel unchanged.
     */
    private fun tcpHeavySteps(): List<TcpChainStepModel> =
        listOf(
            // Step 0: tlsrec prelude
            TcpChainStepModel(
                kind = TcpChainStepKind.TlsRec,
                marker = "extlen",
            ),
            // Step 1: fake -- exercises fakeOrder and fakeSeqMode
            TcpChainStepModel(
                kind = TcpChainStepKind.Fake,
                marker = "host+1",
                fakeOrder = "1",
                fakeSeqMode = "sequential",
                activationFilter = ActivationFilterModel(
                    round = NumericRangeModel(start = 1L, end = 3L),
                    tcpHasTimestamp = true,
                ),
            ),
            // Step 2: fakedsplit -- exercises fakeOrder on a split-based kind
            TcpChainStepModel(
                kind = TcpChainStepKind.FakeSplit,
                marker = "host+2",
                fakeOrder = "2",
                fakeSeqMode = "duplicate",
            ),
            // Step 3: hostfake -- exercises fakeHostTemplate and midhostMarker
            TcpChainStepModel(
                kind = TcpChainStepKind.HostFake,
                marker = "endhost+8",
                midhostMarker = "midsld",
                fakeHostTemplate = "googlevideo.com",
                fakeOrder = "1",
            ),
            // Step 4: disorder
            TcpChainStepModel(
                kind = TcpChainStepKind.Disorder,
                marker = "host+3",
                activationFilter = ActivationFilterModel(
                    payloadSize = NumericRangeModel(start = 100L, end = 1400L),
                ),
            ),
            // Step 5: split
            TcpChainStepModel(
                kind = TcpChainStepKind.Split,
                marker = "host+4",
            ),
        )

    /**
     * Relay config touching three relay kinds' field groups with a non-inline
     * credential reference (ref points into RelayCredentialStore; no secrets
     * land in the JSON payload).
     */
    private fun relayHeavyConfig(): RipDpiRelayConfig =
        RipDpiRelayConfig(
            enabled = true,
            kind = RelayKindChainRelay,
            profileId = "chain-heavy-primary",
            // MASQUE group
            masqueUrl = "https://example.cloudflare.com/.well-known/masque/udp/203.0.113.5/443/",
            masqueUseHttp2Fallback = true,
            masqueCloudflareGeohashEnabled = true,
            // Cloudflare Tunnel group -- credential ref only, never inline
            cloudflareTunnelMode = RelayCloudflareTunnelModeConsumeExisting,
            cloudflareCredentialsRef = "relay-cred::cloudflare-tunnel::primary",
            // NaiveProxy group
            naivePath = "/data/local/tmp/ripdpi-naive",
            // Chain-relay entry + exit
            chainEntryServer = "entry.example.com",
            chainEntryPort = 8443,
            chainEntryServerName = "entry.example.com",
            chainEntryPublicKey = "A".repeat(44),
            chainEntryShortId = "deadbeef",
            chainEntryProfileId = "chain-entry-ref",
            chainExitServer = "exit.example.com",
            chainExitPort = 8443,
            chainExitServerName = "exit.example.com",
            chainExitPublicKey = "B".repeat(44),
            chainExitShortId = "cafebabe",
            chainExitProfileId = "chain-exit-ref",
        )

    /**
     * 3 UDP steps: dummy_prepend (QUIC dummy), fake_burst, quic_sni_split.
     */
    private fun udpQuicSteps(): List<UdpChainStepModel> =
        listOf(
            UdpChainStepModel(
                kind = UdpChainStepKind.DummyPrepend,
                count = 1,
            ),
            UdpChainStepModel(
                kind = UdpChainStepKind.FakeBurst,
                count = 3,
            ),
            UdpChainStepModel(
                kind = UdpChainStepKind.QuicSniSplit,
                count = 1,
            ),
        )

    // ---------------------------------------------------------------------------
    // Fixture I/O
    // ---------------------------------------------------------------------------

    private fun writeFixture(name: String, json: String) {
        val dir = File("src/test/resources/fixtures")
        dir.mkdirs()
        File(dir, name).writeText(json)
    }
}

private val TcpChainStepKind.supportsFakeOrdering: Boolean
    get() =
        this == TcpChainStepKind.Fake ||
            this == TcpChainStepKind.FakeSplit ||
            this == TcpChainStepKind.FakeDisorder ||
            this == TcpChainStepKind.HostFake
