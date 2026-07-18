package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyConfigRouteTest {
    @Test
    fun luaStrategyConfigYamlPersistsTunnelReadableLuaStep() {
        val yaml =
            luaStrategyConfigYaml(
                function = "candidate",
                scriptPath = "/data/user/0/com.poyka.ripdpi/files/lua/candidate.lua",
            )

        assertEquals(
            """
            version: 1
            strategies:
              - id: "lua:candidate"
                steps:
                  - type: lua
                    function: "candidate"
                    script_paths:
                      - "/data/user/0/com.poyka.ripdpi/files/lua/candidate.lua"
            """.trimIndent(),
            yaml,
        )
    }

    @Test
    fun `back and up wait for hydration or active save before deciding`() {
        var dirtyRequested = false
        var navigatedBack = false
        val request = { blocked: Boolean, dirty: Boolean ->
            requestStrategyConfigExit(
                blocked = blocked,
                isDirty = dirty,
                onDirty = { dirtyRequested = true },
                onBack = { navigatedBack = true },
            )
        }

        request(true, true)
        assertFalse(dirtyRequested)
        assertFalse(navigatedBack)

        request(false, true)
        assertTrue(dirtyRequested)
        assertFalse(navigatedBack)

        dirtyRequested = false
        request(false, false)
        assertFalse(dirtyRequested)
        assertTrue(navigatedBack)
    }
}
