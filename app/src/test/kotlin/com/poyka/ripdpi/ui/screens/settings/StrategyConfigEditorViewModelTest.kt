package com.poyka.ripdpi.ui.screens.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyConfigEditorViewModelTest {
    @Test
    fun `retained state holder preserves the complete dirty draft`() {
        val viewModel = StrategyConfigEditorViewModel()
        viewModel.syncBuiltIn("tcp: split")
        viewModel.selectSource(StrategyConfigSource.LuaScript, "tcp: split")
        viewModel.update {
            copy(
                configText = "version: 1",
                luaPath = "custom.lua",
                luaFunction = "route",
            )
        }

        viewModel.syncBuiltIn("tcp: late")

        val session = requireNotNull(viewModel.session)
        assertEquals(StrategyConfigSource.LuaScript, session.draft.source)
        assertEquals("version: 1", session.draft.configText)
        assertEquals("custom.lua", session.draft.luaPath)
        assertEquals("route", session.draft.luaFunction)
        assertTrue(session.isDirty)
    }

    @Test
    fun `cancelled route save releases the retained session`() =
        runTest {
            val viewModel = StrategyConfigEditorViewModel()
            viewModel.syncBuiltIn("tcp: split")
            viewModel.update { copy(configText = "tcp: disorder") }
            val request = requireNotNull(viewModel.beginSave())

            var cancelled = false
            try {
                viewModel.runSave(request) { throw CancellationException("recreated") }
            } catch (_: CancellationException) {
                cancelled = true
            }

            val session = requireNotNull(viewModel.session)
            assertTrue(cancelled)
            assertFalse(session.isSaving)
            assertTrue(session.isDirty)
        }
}
