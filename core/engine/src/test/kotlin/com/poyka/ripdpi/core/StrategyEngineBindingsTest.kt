package com.poyka.ripdpi.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StrategyEngineBindingsTest {
    @Test
    fun fakeBindingsExposeLuaManagementContract() {
        val bindings =
            FakeStrategyEngineBindings(
                loadResult = "missing script",
                validateResult = null,
                strategies = arrayOf("split_host", "pass"),
            )

        assertEquals("missing script", bindings.luaLoadScript("/files/lua", "/missing.lua"))
        assertNull(bindings.luaValidateScript("/valid.lua"))
        assertNull(bindings.validateStrategyConfigText("version: 1\nstrategies: []"))
        assertNull(bindings.luaReloadConfig())
        assertArrayEquals(arrayOf("split_host", "pass"), bindings.luaListStrategies())
        assertArrayEquals(arrayOf("/tmp/strategy.lua"), bindings.luaLoadedScriptPaths())
        assertNull(
            bindings.injectProbeResults(
                arrayOf(StrategyProbeResultDto("fake", "youtube.com", success = true, latencyMs = 80)),
            ),
        )
    }

    @Test
    fun processGlobalBindingsSerializeConcurrentReloadCalls() =
        runBlocking {
            val delegate = BlockingReloadBindings()
            val firstBindings = ProcessGlobalStrategyEngineBindings(delegate)
            val secondBindings = ProcessGlobalStrategyEngineBindings(delegate)

            val firstReload = async(Dispatchers.Default) { firstBindings.luaReloadConfig() }
            assertTrue(delegate.firstCallEntered.await(5, TimeUnit.SECONDS))

            val secondReload = async(Dispatchers.Default) { secondBindings.luaReloadConfig() }
            assertFalse(delegate.secondCallEntered.await(150, TimeUnit.MILLISECONDS))

            delegate.releaseFirstCall.countDown()
            firstReload.await()
            secondReload.await()

            assertTrue(delegate.secondCallEntered.await(5, TimeUnit.SECONDS))
            assertFalse(delegate.overlapDetected.get())
            assertEquals(2, delegate.callCount.get())
        }
}

private class FakeStrategyEngineBindings(
    private val loadResult: String?,
    private val validateResult: String?,
    private val strategies: Array<String>,
) : StrategyEngineBindings {
    override fun luaLoadScript(
        baseDir: String,
        path: String,
    ): String? = loadResult

    override fun luaReloadConfig(): String? = null

    override fun luaListStrategies(): Array<String> = strategies

    override fun luaLoadedScriptPaths(): Array<String> = arrayOf("/tmp/strategy.lua")

    override fun luaValidateScript(path: String): String? = validateResult

    override fun validateStrategyConfigText(configText: String): String? = validateResult

    override fun injectProbeResults(results: Array<StrategyProbeResultDto>): String? = null
}

private class BlockingReloadBindings : StrategyEngineBindings {
    val firstCallEntered = CountDownLatch(1)
    val secondCallEntered = CountDownLatch(1)
    val releaseFirstCall = CountDownLatch(1)
    val overlapDetected = AtomicBoolean(false)
    val callCount = AtomicInteger(0)

    private val inFlight = AtomicInteger(0)

    override fun luaLoadScript(
        baseDir: String,
        path: String,
    ): String? = null

    override fun luaReloadConfig(): String? {
        if (inFlight.incrementAndGet() > 1) {
            overlapDetected.set(true)
        }
        val call = callCount.incrementAndGet()
        if (call == 1) {
            firstCallEntered.countDown()
            assertTrue(releaseFirstCall.await(5, TimeUnit.SECONDS))
        } else {
            secondCallEntered.countDown()
        }
        inFlight.decrementAndGet()
        return null
    }

    override fun luaListStrategies(): Array<String> = emptyArray()

    override fun luaLoadedScriptPaths(): Array<String> = emptyArray()

    override fun luaValidateScript(path: String): String? = null

    override fun validateStrategyConfigText(configText: String): String? = null

    override fun injectProbeResults(results: Array<StrategyProbeResultDto>): String? = null
}
