package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
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
}
