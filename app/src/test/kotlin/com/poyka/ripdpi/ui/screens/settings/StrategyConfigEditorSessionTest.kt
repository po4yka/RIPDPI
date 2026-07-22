package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyConfigEditorSessionTest {
    private val baseline =
        StrategyConfigDraft(
            source = StrategyConfigSource.BuiltIn,
            configText = "tcp: split",
            luaPath = "",
            luaFunction = "",
        )

    @Test
    fun `dirty state compares the complete draft and clears on revert`() {
        val session = StrategyConfigEditorSession(baseline)

        val edited = session.update { copy(luaPath = "custom.lua", luaFunction = "route") }
        val reverted = edited.update { baseline }

        assertTrue(edited.isDirty)
        assertFalse(reverted.isDirty)
    }

    @Test
    fun `built-in updates sync only while the editor is clean`() {
        val session = StrategyConfigEditorSession(baseline)
        val synced = session.syncCleanBuiltIn("tcp: disorder")
        val dirty = synced.update { copy(configText = "tcp: custom") }

        assertEquals("tcp: disorder", synced.baseline.configText)
        assertEquals("tcp: disorder", synced.draft.configText)
        assertSame(dirty, dirty.syncCleanBuiltIn("tcp: late"))
    }

    @Test
    fun `successful save advances baseline only to the submitted snapshot`() {
        val edited = StrategyConfigEditorSession(baseline).update { copy(configText = "tcp: first") }
        val (saving, request) = requireNotNull(edited.beginSave())
        val newer = saving.update { copy(configText = "tcp: second") }

        val completed = newer.completeSave(request, succeeded = true)

        assertEquals("tcp: first", completed.baseline.configText)
        assertEquals("tcp: second", completed.draft.configText)
        assertTrue(completed.isDirty)
        assertFalse(completed.isSaving)
    }

    @Test
    fun `failed save preserves baseline and releases admission`() {
        val edited = StrategyConfigEditorSession(baseline).update { copy(configText = "invalid") }
        val (saving, request) = requireNotNull(edited.beginSave())

        val completed = saving.completeSave(request, succeeded = false)

        assertEquals(baseline, completed.baseline)
        assertTrue(completed.isDirty)
        assertFalse(completed.isSaving)
        assertTrue(completed.beginSave() != null)
    }

    @Test
    fun `duplicate save is rejected while a request is active`() {
        val (saving, _) = requireNotNull(StrategyConfigEditorSession(baseline).beginSave())

        assertEquals(null, saving.beginSave())
    }
}
