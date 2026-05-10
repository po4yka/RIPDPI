package com.poyka.ripdpi.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrategyEngineBindingsTest {
    @Test
    fun fakeBindingsExposeLuaManagementContract() {
        val bindings =
            FakeStrategyEngineBindings(
                loadResult = "missing script",
                validateResult = null,
                strategies = arrayOf("split_host", "pass"),
            )

        assertEquals("missing script", bindings.luaLoadScript("/missing.lua"))
        assertNull(bindings.luaValidateScript("/valid.lua"))
        assertNull(bindings.luaReloadConfig())
        assertArrayEquals(arrayOf("split_host", "pass"), bindings.luaListStrategies())
    }
}

private class FakeStrategyEngineBindings(
    private val loadResult: String?,
    private val validateResult: String?,
    private val strategies: Array<String>,
) : StrategyEngineBindings {
    override fun luaLoadScript(path: String): String? = loadResult

    override fun luaReloadConfig(): String? = null

    override fun luaListStrategies(): Array<String> = strategies

    override fun luaValidateScript(path: String): String? = validateResult
}
