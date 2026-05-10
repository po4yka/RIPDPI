package com.poyka.ripdpi.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LuaAssetManagerTest {
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("lua").deleteRecursively()
    }

    @Test
    fun ensureExtractedCopiesBundledLuaFiles() {
        val luaDir = LuaAssetManager.ensureExtracted(context).toFile()

        assertTrue(File(luaDir, "zapret-antidpi.lua").isFile)
        assertTrue(File(luaDir, "zapret-lib.lua").isFile)
        assertTrue(File(luaDir, "LICENSE.zapret2.txt").isFile)
        assertTrue(File(luaDir, "lua-manifest.json").isFile)
    }

    @Test
    fun currentManifestVersionReturnsBundledManifestVersion() {
        assertEquals(
            "zapret2-fd4716da5426550a3354b1b179f3dda446811a13",
            LuaAssetManager.currentManifestVersion(context),
        )
    }

    @Test
    fun extractIfOutdatedBacksUpUserModifiedScriptsBeforeOverwrite() {
        val luaDir = LuaAssetManager.ensureExtracted(context).toFile()
        val script = File(luaDir, "zapret-antidpi.lua")
        script.writeText("-- user modified")
        File(luaDir, "lua-manifest.json")
            .writeText(
                """
                {
                  "version": "old",
                  "files": ["zapret-antidpi.lua"],
                  "license_file": "LICENSE.zapret2.txt"
                }
                """.trimIndent(),
            )

        LuaAssetManager.extractIfOutdated(context)

        val backup = File(luaDir, "zapret-antidpi.lua.user.bak")
        assertTrue(backup.isFile)
        assertEquals("-- user modified", backup.readText())
        assertTrue(script.readText().contains("NFQWS2 ANTIDPI LIBRARY"))
    }
}
