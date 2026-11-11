package com.poyka.ripdpi.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpSocketProtectionSerializationTest {
    private val json = Json
    private val probe =
        WarpEndpointProbeNativeRequest(
            endpoint = ResolvedRipDpiWarpEndpoint("127.0.0.1", port = 2408),
            privateKey = "synthetic-private-key",
            peerPublicKey = "synthetic-peer-key",
            timeoutMs = 250,
        )

    @Test
    fun omittedProbePolicyRequiresVpnProtection() {
        val encoded = json.parseToJsonElement(json.encodeToString(probe)).jsonObject
        val withoutPolicy = JsonObject(encoded - "socketProtection")
        val decoded = json.decodeFromString(WarpEndpointProbeNativeRequest.serializer(), withoutPolicy.toString())
        assertEquals(RelaySocketProtection.VpnRequired, decoded.socketProtection)
    }

    @Test
    fun inactiveProbePolicyIsExplicitAndUnknownPolicyIsRejected() {
        val inactive = probe.copy(socketProtection = RelaySocketProtection.Inactive)
        val encoded = json.parseToJsonElement(json.encodeToString(inactive)).jsonObject
        assertEquals(JsonPrimitive("inactive"), encoded["socketProtection"])
        assertEquals(
            RelaySocketProtection.Inactive,
            json.decodeFromString(WarpEndpointProbeNativeRequest.serializer(), encoded.toString()).socketProtection,
        )
        val invalid = JsonObject(encoded + ("socketProtection" to JsonPrimitive("unknown")))
        assertTrue(
            runCatching {
                json.decodeFromString(WarpEndpointProbeNativeRequest.serializer(), invalid.toString())
            }.isFailure,
        )
    }
}
