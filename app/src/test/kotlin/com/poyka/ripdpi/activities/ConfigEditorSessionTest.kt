package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigEditorSessionTest {
    @Test
    fun `hydrated baseline is clean and edits are dirty`() {
        val baseline = ConfigDraft(proxyPort = "1080")
        val session =
            ConfigEditorSession(
                sessionId = 1L,
                presetId = "custom",
                baselineDraft = baseline,
                draft = baseline,
            )

        assertFalse(session.isDirty)
        assertTrue(session.copy(draft = baseline.copy(proxyPort = "1081")).isDirty)
        assertFalse(session.copy(draft = baseline.copy(proxyPort = "1080")).isDirty)
    }

    @Test
    fun `stale hydration cannot overwrite a newer same-preset session`() {
        val newerDraft = ConfigDraft(proxyPort = "1082")
        val newerSession =
            ConfigEditorSession(
                sessionId = 2L,
                presetId = "custom",
                draft = newerDraft,
                hydrationPending = true,
            )

        val unchanged = newerSession.completeHydration(expectedSessionId = 1L, hydratedDraft = ConfigDraft())

        assertSame(newerSession, unchanged)
        assertEquals(newerDraft, unchanged.draft)
        assertTrue(unchanged.hydrationPending)
    }

    @Test
    fun `matching hydration establishes baseline and draft atomically`() {
        val hydrated = ConfigDraft(proxyPort = "1083")
        val session =
            ConfigEditorSession(
                sessionId = 3L,
                presetId = "custom",
                draft = ConfigDraft(),
                hydrationPending = true,
            )

        val completed = session.completeHydration(expectedSessionId = 3L, hydratedDraft = hydrated)

        assertEquals(hydrated, completed.baselineDraft)
        assertEquals(hydrated, completed.draft)
        assertFalse(completed.hydrationPending)
        assertFalse(completed.isDirty)
    }
}
