package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RelaySecurityLayerNormalizerTest {
    @Test
    fun `normalizeRelayKind accepts the plain vless kind`() {
        assertEquals(RelayKindVless, normalizeRelayKind("vless"))
        assertEquals(RelayKindVless, normalizeRelayKind("  VLESS  "))
        assertEquals(RelayKindVlessReality, normalizeRelayKind("vless_reality"))
    }

    @Test
    fun `normalizeRelaySecurityLayer recognises tls and reality`() {
        assertEquals(RelaySecurityLayerTls, normalizeRelaySecurityLayer("tls"))
        assertEquals(RelaySecurityLayerTls, normalizeRelaySecurityLayer("  TLS "))
        assertEquals(RelaySecurityLayerReality, normalizeRelaySecurityLayer("reality"))
    }

    @Test
    fun `normalizeRelaySecurityLayer infers reality for the reality kind`() {
        assertEquals(
            RelaySecurityLayerReality,
            normalizeRelaySecurityLayer("", relayKind = RelayKindVlessReality),
        )
    }

    @Test
    fun `normalizeRelaySecurityLayer infers tls for the plain vless kind`() {
        assertEquals(
            RelaySecurityLayerTls,
            normalizeRelaySecurityLayer("", relayKind = RelayKindVless),
        )
    }

    @Test
    fun `normalizeRelaySecurityLayer falls back to reality when unspecified`() {
        // No kind hint, no value: preserve today's reality-only semantics.
        assertEquals(RelaySecurityLayerReality, normalizeRelaySecurityLayer(""))
        assertEquals(RelaySecurityLayerReality, normalizeRelaySecurityLayer("bogus"))
    }

    @Test
    fun `normalizeRelayVlessTransport allows xhttp for the plain vless kind`() {
        assertEquals(
            RelayVlessTransportXhttp,
            normalizeRelayVlessTransport(RelayVlessTransportXhttp, RelayKindVless),
        )
        // a plain-tls vless profile defaults to tcp transport when xhttp is not requested
        assertEquals(
            RelayVlessTransportRealityTcp,
            normalizeRelayVlessTransport("", RelayKindVless),
        )
    }
}
