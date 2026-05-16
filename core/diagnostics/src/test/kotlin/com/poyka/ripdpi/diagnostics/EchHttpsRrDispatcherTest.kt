package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.ech.EchHttpsRrDispatcher
import com.poyka.ripdpi.diagnostics.ech.EchMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EchHttpsRrDispatcherTest {
    private val queriedHosts = mutableListOf<String>()

    private val fakeQuery: suspend (String) -> Unit = { host ->
        queriedHosts.add(host)
    }

    private fun dispatcher() = EchHttpsRrDispatcher(httpsRrQuery = fakeQuery)

    @Test
    fun `enabled mode triggers HTTPS RR query`() =
        runTest {
            dispatcher().dispatch("enabled.example.com", EchMode.Enabled)
            assertTrue(queriedHosts.contains("enabled.example.com"))
        }

    @Test
    fun `opportunistic mode triggers HTTPS RR query`() =
        runTest {
            dispatcher().dispatch("opportunistic.example.com", EchMode.Opportunistic)
            assertTrue(queriedHosts.contains("opportunistic.example.com"))
        }

    @Test
    fun `disabled mode does NOT trigger HTTPS RR query`() =
        runTest {
            dispatcher().dispatch("disabled.example.com", EchMode.Disabled)
            assertTrue(queriedHosts.isEmpty())
        }

    @Test
    fun `dispatch all skips disabled and queries enabled and opportunistic`() =
        runTest {
            val domainModes =
                mapOf(
                    "a.example.com" to EchMode.Enabled,
                    "b.example.com" to EchMode.Disabled,
                    "c.example.com" to EchMode.Opportunistic,
                )
            dispatcher().dispatchAll(domainModes)
            assertEquals(2, queriedHosts.size)
            assertTrue(queriedHosts.contains("a.example.com"))
            assertTrue(queriedHosts.contains("c.example.com"))
            assertTrue(!queriedHosts.contains("b.example.com"))
        }

    @Test
    fun `dispatch all with all disabled produces no queries`() =
        runTest {
            val domainModes =
                mapOf(
                    "x.com" to EchMode.Disabled,
                    "y.com" to EchMode.Disabled,
                )
            dispatcher().dispatchAll(domainModes)
            assertTrue(queriedHosts.isEmpty())
        }

    @Test
    fun `dispatch all with all enabled queries all domains`() =
        runTest {
            val domainModes =
                mapOf(
                    "p.com" to EchMode.Enabled,
                    "q.com" to EchMode.Enabled,
                )
            dispatcher().dispatchAll(domainModes)
            assertEquals(2, queriedHosts.size)
        }
}
