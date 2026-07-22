package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyConfigDraftViewModelTest {
    @Test
    fun `custom draft is not replaced when persisted built in value refreshes`() {
        val viewModel = StrategyConfigDraftViewModel()

        viewModel.synchronizeBuiltIn("tcp=split")
        viewModel.selectSource(StrategyConfigSource.CustomYaml, "tcp=split")
        viewModel.updateConfigText("custom: draft")
        viewModel.updateLuaPath("/data/local/custom.lua")
        viewModel.updateLuaFunction("apply")
        viewModel.synchronizeBuiltIn("tcp=fake")

        assertEquals(StrategyConfigSource.CustomYaml, viewModel.state.value.source)
        assertEquals("custom: draft", viewModel.state.value.configText)
        assertEquals("/data/local/custom.lua", viewModel.state.value.luaPath)
        assertEquals("apply", viewModel.state.value.luaFunction)
    }

    @Test
    fun `selecting built in restores current persisted chain`() {
        val viewModel = StrategyConfigDraftViewModel()
        viewModel.applyImportedConfig("custom: draft")

        viewModel.selectSource(StrategyConfigSource.BuiltIn, "tcp=disorder")

        assertEquals(StrategyConfigSource.BuiltIn, viewModel.state.value.source)
        assertEquals("tcp=disorder", viewModel.state.value.configText)
    }
}
