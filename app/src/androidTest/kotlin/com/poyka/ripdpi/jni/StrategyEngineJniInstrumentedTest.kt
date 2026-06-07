package com.poyka.ripdpi.jni

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.core.ProcessGlobalStrategyEngineBindings
import com.poyka.ripdpi.lua.LuaAssetManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StrategyEngineJniInstrumentedTest {
    @Test
    fun luaLoadScriptLoadsBundledZapretScriptsAfterExtraction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val luaDir = LuaAssetManager.ensureExtracted(context).toFile()
        val bindings = ProcessGlobalStrategyEngineBindings()

        assertNull(bindings.luaValidateScript(File(luaDir, "zapret-lib.lua").absolutePath))
        assertNull(bindings.luaLoadScript(File(luaDir, "zapret-lib.lua").absolutePath))
        assertNull(bindings.luaLoadScript(File(luaDir, "zapret-antidpi.lua").absolutePath))

        assertTrue(bindings.luaListStrategies().contains("multisplit"))
    }

    @Test
    fun luaLoadScriptMissingPathReturnsErrorString() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missingPath = File(context.filesDir, "lua/missing.lua").absolutePath

        assertNotNull(ProcessGlobalStrategyEngineBindings().luaLoadScript(missingPath))
    }

    @Test
    fun strategyConfigValidationAcceptsRegistryYaml() {
        val yaml =
            """
            version: 1
            strategies:
              - id: fake_chain
                steps:
                  - type: fake
              - id: quic_udplen
                match:
                  proto: [quic]
                steps:
                  - type: udplen
                    delta: 4
              - id: tcp_ipv6_ext
                match:
                  proto: [tls]
                steps:
                  - type: ipv6Ext
                    ext_type: destopts
            """.trimIndent()

        assertNull(ProcessGlobalStrategyEngineBindings().validateStrategyConfigText(yaml))
    }

    @Test
    fun strategyConfigValidationRejectsInvalidYaml() {
        assertNotNull(ProcessGlobalStrategyEngineBindings().validateStrategyConfigText("version: ["))
    }
}
