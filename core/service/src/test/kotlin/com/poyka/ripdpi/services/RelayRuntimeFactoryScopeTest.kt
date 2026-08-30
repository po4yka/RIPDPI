package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider
import javax.inject.Singleton

class RelayRuntimeFactoryScopeTest {
    @Test
    fun `relay runtime factories do not retain mutable runtime instances across sessions`() {
        assertFalse(GoogleAppsScriptRelayRuntime::class.java.isAnnotationPresent(Singleton::class.java))
        assertUsesRuntimeProvider(DefaultGoogleAppsScriptRelayRuntimeFactory::class.java)
        assertUsesRuntimeProvider(DefaultNaiveProxyRuntimeFactory::class.java)
        assertUsesRuntimeProvider(DefaultPluggableTransportRuntimeFactory::class.java)
    }

    @Test
    fun `relay runtime factories request a new runtime for every create`() {
        var googleCalls = 0
        val googleFactory =
            DefaultGoogleAppsScriptRelayRuntimeFactory(
                Provider {
                    googleCalls += 1
                    error("google-$googleCalls")
                },
            )
        assertThrows(IllegalStateException::class.java) { googleFactory.create() }
        assertThrows(IllegalStateException::class.java) { googleFactory.create() }
        assertEquals(2, googleCalls)

        var naiveCalls = 0
        val naiveFactory =
            DefaultNaiveProxyRuntimeFactory(
                Provider {
                    naiveCalls += 1
                    error("naive-$naiveCalls")
                },
            )
        assertThrows(IllegalStateException::class.java) { naiveFactory.create() }
        assertThrows(IllegalStateException::class.java) { naiveFactory.create() }
        assertEquals(2, naiveCalls)

        var transportCalls = 0
        val transportFactory =
            DefaultPluggableTransportRuntimeFactory(
                Provider {
                    transportCalls += 1
                    error("transport-$transportCalls")
                },
            )
        assertThrows(IllegalStateException::class.java) { transportFactory.create() }
        assertThrows(IllegalStateException::class.java) { transportFactory.create() }
        assertEquals(2, transportCalls)
    }

    private fun assertUsesRuntimeProvider(factoryClass: Class<*>) {
        assertTrue(
            "${factoryClass.simpleName} must request each runtime through Provider",
            factoryClass.declaredConstructors
                .single()
                .parameterTypes
                .single() == Provider::class.java,
        )
    }
}
