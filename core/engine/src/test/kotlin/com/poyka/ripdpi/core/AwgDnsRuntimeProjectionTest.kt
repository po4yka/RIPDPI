package com.poyka.ripdpi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgDnsRuntimeProjectionTest {
    @Test
    fun activeProjectionCopiesResolversAcrossUiJsonAndSessionPreferences() {
        val resolvers = mutableListOf("10.77.0.1", "fd77::1")
        val base = RipDpiProxyUIPreferences()
        val inputs =
            listOf(
                base,
                RipDpiProxyJsonPreferences(base.toNativeConfigJson()),
                base.withLocalProxySessionOverrides(18081, "test-token"),
            )
        val projected = inputs.map { it.withAwgEgressPort(10809, resolvers) }
        resolvers.clear()
        projected.forEach {
            val decoded = requireNotNull(decodeRipDpiProxyUiPreferences(it.toNativeConfigJson()))
            assertEquals(listOf("10.77.0.1", "fd77::1"), decoded.warp.awgDnsServers)
            assertEquals(AmneziaWgAllTrafficRouteHosts, decoded.warp.routeHosts)
            assertEquals(base.destinationRouting, decoded.destinationRouting)
        }
        assertTrue(projected.last().toNativeConfigJson().contains("test-token"))
    }

    @Test
    fun emptyAuthorityIsOmittedAndRememberedAuthorityCanBeCleared() {
        val active = RipDpiProxyUIPreferences().withAwgEgressPort(10809, listOf("10.77.0.1"))
        val remembered = RipDpiProxyJsonPreferences(active.toNativeConfigJson())
        listOf(RipDpiProxyUIPreferences(), active.withoutAwgDnsServers(), remembered.withoutAwgDnsServers()).forEach {
            assertFalse(it.toNativeConfigJson().contains("awgDnsServers"))
            assertTrue(
                requireNotNull(decodeRipDpiProxyUiPreferences(it.toNativeConfigJson())).warp.awgDnsServers.isEmpty(),
            )
        }
        val switched = remembered.withAwgEgressPort(10809, listOf("fd77::2"))
        assertEquals(
            listOf("fd77::2"),
            requireNotNull(decodeRipDpiProxyUiPreferences(switched.toNativeConfigJson())).warp.awgDnsServers,
        )
    }

    @Test
    fun normalizationCopiesInputAndRejectsInvalidUpstreamInsteadOfRepairingAuthority() {
        val resolvers = mutableListOf("10.77.0.1")
        val warp = activeWarp().copy(awgDnsServers = resolvers)
        val preferences = RipDpiProxyUIPreferences(warp = warp)
        resolvers.clear()
        assertEquals(listOf("10.77.0.1"), preferences.warp.awgDnsServers)
        listOf(activeWarp().copy(localSocksPort = 0), activeWarp().copy(localSocksHost = "")).forEach {
            assertThrows(
                IllegalArgumentException::class.java,
            ) { RipDpiProxyUIPreferences(warp = it).toNativeConfigJson() }
        }
    }

    @Test
    fun codecRejectsInvalidResolverShapeAndInactiveAuthority() {
        val active =
            Json
                .parseToJsonElement(
                    RipDpiProxyUIPreferences(warp = activeWarp()).toNativeConfigJson(),
                ).jsonObject
        val warp = active.getValue("warp").jsonObject
        val invalid =
            listOf(
                "awgDnsServers" to JsonNull,
                "awgDnsServers" to JsonPrimitive("10.77.0.1"),
                "awgDnsServers" to JsonArray(listOf(JsonPrimitive(42))),
                "awgDnsServers" to JsonArray(listOf(JsonPrimitive("resolver.example"))),
                "awgDnsServers" to JsonArray(listOf(JsonPrimitive("[fd77::1]"))),
                "awgDnsServers" to JsonArray(listOf(JsonPrimitive("10.77.0.1/32"))),
                "enabled" to JsonPrimitive(false),
                "routeMode" to JsonPrimitive("off"),
                "routeHosts" to JsonPrimitive("example.org"),
                "localSocksHost" to JsonPrimitive("192.0.2.1"),
                "localSocksHost" to JsonPrimitive("localhost"),
                "localSocksPort" to JsonPrimitive(0),
            )
        invalid.forEach { (key, value) ->
            val input = JsonObject(active + ("warp" to JsonObject(warp + (key to value)))).toString()
            assertTrue(
                "Accepted invalid $key=$value",
                runCatching {
                    RipDpiProxyJsonPreferences(input).toNativeConfigJson()
                }.isFailure,
            )
        }
    }

    private fun activeWarp(): RipDpiWarpConfig =
        RipDpiWarpConfig(
            enabled = true,
            routeMode = "rules",
            routeHosts = AmneziaWgAllTrafficRouteHosts,
            awgDnsServers = listOf("10.77.0.1", "fd77::1"),
        )
}
