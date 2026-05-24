package com.poyka.ripdpi.diagnostics.replay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [ReplayRecommendationEngine] against the production JSON
 * catalog. Loads the asset via the classpath copy under
 * `src/test/resources/diagnostics/replay_recommendations.json`, which
 * mirrors `src/main/assets/diagnostics/replay_recommendations.json`
 * byte-for-byte. This avoids the Android [android.content.Context]
 * dependency and keeps the test runnable in a plain JVM harness.
 */
class ReplayRecommendationEngineTest {
    private val catalog =
        ReplayRecommendationCatalogParser.parse(
            javaClass
                .getResourceAsStream("/diagnostics/replay_recommendations.json")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("test fixture diagnostics/replay_recommendations.json missing from classpath"),
        )
    private val engine = ReplayRecommendationEngine.forTesting(catalog)

    @Test
    fun catalog_loadsExpectedVersionAndDefault() {
        assertEquals(1, catalog.version)
        assertEquals("vpn_replay_rec_generic_fallback", catalog.defaultRecommendationKey)
        // Sanity check: every rule in the catalog parsed into the typed
        // enum form, so an out-of-vocabulary step / errorKind in the
        // JSON would fail this test fast rather than silently degrade.
        assertEquals(7, catalog.rules.size)
    }

    @Test
    fun tlsHandshakeReset_returnsSniInspectionKey() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = ReplayStepKind.TlsHandshake,
                errorKind = ReplayErrorKind.ConnectionReset,
            )
        assertEquals("vpn_replay_rec_rst_after_client_hello", key)
    }

    @Test
    fun tlsClientHelloReset_returnsSniInspectionKey() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = ReplayStepKind.TlsClientHello,
                errorKind = ReplayErrorKind.ConnectionReset,
            )
        assertEquals("vpn_replay_rec_rst_after_client_hello", key)
    }

    @Test
    fun dnsTampered_returnsDnsKey() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = ReplayStepKind.DnsResolve,
                errorKind = ReplayErrorKind.DnsTampered,
            )
        assertEquals("vpn_replay_rec_dns_tampered", key)
    }

    @Test
    fun tcpRefused_returnsTcpUnreachableKey() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = ReplayStepKind.TcpOpen,
                errorKind = ReplayErrorKind.ConnectionRefused,
            )
        assertEquals("vpn_replay_rec_tcp_unreachable", key)
    }

    @Test
    fun tcpTimeout_returnsTcpUnreachableKey() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = ReplayStepKind.TcpOpen,
                errorKind = ReplayErrorKind.Timeout,
            )
        assertEquals("vpn_replay_rec_tcp_unreachable", key)
    }

    @Test
    fun tlsHandshakeFailed_returnsHandshakeAlertKey() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = ReplayStepKind.TlsHandshake,
                errorKind = ReplayErrorKind.TlsHandshakeFailed,
            )
        assertEquals("vpn_replay_rec_handshake_alert", key)
    }

    @Test
    fun firstByteTimeout_returnsTimeoutKey() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = ReplayStepKind.FirstByte,
                errorKind = ReplayErrorKind.Timeout,
            )
        assertEquals("vpn_replay_rec_timeout_no_response", key)
    }

    @Test
    fun unknownPair_returnsGenericFallback() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = ReplayStepKind.FirstByte,
                errorKind = ReplayErrorKind.Unknown,
            )
        assertEquals("vpn_replay_rec_generic_fallback", key)
    }

    @Test
    fun nullTerminalStep_returnsGenericFallback() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = null,
                errorKind = ReplayErrorKind.ConnectionReset,
            )
        assertEquals("vpn_replay_rec_generic_fallback", key)
    }

    @Test
    fun nullErrorKind_returnsGenericFallback() {
        val key =
            engine.recommendationKeyFor(
                terminalStep = ReplayStepKind.TlsHandshake,
                errorKind = null,
            )
        assertEquals("vpn_replay_rec_generic_fallback", key)
    }

    @Test
    fun parser_acceptsExtraFields_perIgnoreUnknownKeysContract() {
        val withExtra =
            """
            {
              "version": 1,
              "rules": [],
              "defaultRecommendationKey": "k",
              "futureField": "ignored"
            }
            """.trimIndent()
        val parsed = ReplayRecommendationCatalogParser.parse(withExtra)
        assertEquals("k", parsed.defaultRecommendationKey)
        assertEquals(0, parsed.rules.size)
    }
}
