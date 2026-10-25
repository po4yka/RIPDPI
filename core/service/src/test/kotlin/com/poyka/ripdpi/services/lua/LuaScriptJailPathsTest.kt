package com.poyka.ripdpi.services.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LuaScriptJailPathsTest {
    @Test
    fun `lua script path is stored relative to the jail the runtime loads from`() {
        val root = Files.createTempDirectory("lua-jail").toFile()
        try {
            val jail = File(root, "files/lua").apply { mkdirs() }
            File(jail, "zapret-antidpi.lua").writeText("-- script")
            val alias = File(root, "alias")
            Files.createSymbolicLink(alias.toPath(), File(root, "files").toPath())

            val absolute = "${jail.path}/zapret-antidpi.lua"

            assertEquals("zapret-antidpi.lua", luaJailRelativeScriptPath(jail.path, absolute))
            assertEquals("zapret-antidpi.lua", luaJailRelativeScriptPath(jail.path, "zapret-antidpi.lua"))
            assertEquals("zapret-antidpi.lua", luaJailRelativeScriptPath("${alias.path}/lua", absolute))
            assertNull(luaJailRelativeScriptPath(jail.path, "../outside.lua"))
            assertNull(luaJailRelativeScriptPath(jail.path, "${root.path}/outside.lua"))
            assertNull(luaJailRelativeScriptPath(jail.path, jail.path))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `saved absolute in-jail script paths load as jail-relative paths`() {
        val root = Files.createTempDirectory("lua-jail").toFile()
        try {
            val jail = File(root, "files/lua").apply { mkdirs() }
            File(jail, "zapret-antidpi.lua").writeText("-- script")
            val saved =
                """
                version: 1
                strategies:
                  - id: "lua:candidate"
                    steps:
                      - type: lua
                        function: "candidate"
                        script_paths:
                          - "${jail.path}/zapret-antidpi.lua"
                          - ${root.path}/outside.lua
                          - "relative.lua"
                """.trimIndent()

            val loaded = relativizeLuaScriptPaths(saved, jail.path)

            assertEquals(
                saved
                    .replace("\"${jail.path}/zapret-antidpi.lua\"", "\"zapret-antidpi.lua\""),
                loaded,
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
