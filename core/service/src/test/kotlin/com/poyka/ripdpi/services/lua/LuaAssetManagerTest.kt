package com.poyka.ripdpi.services.lua

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LuaAssetManagerTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `a manifest torn by a kill mid-write is re-extracted instead of failing every start`() {
        val installDir = File(application.filesDir, "lua").apply { mkdirs() }
        val manifest = File(installDir, "lua-manifest.json")
        manifest.writeText("{\"version\": \"")

        val extracted = LuaAssetManager.ensureExtracted(application).toFile()

        val shipped = application.assets.open("lua/lua-manifest.json").use { it.readBytes() }
        assertTrue(manifest.readBytes().contentEquals(shipped))
        assertEquals(installDir.canonicalPath, extracted.canonicalPath)
    }
}
