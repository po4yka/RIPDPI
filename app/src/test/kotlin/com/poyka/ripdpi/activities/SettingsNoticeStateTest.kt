package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNoticeStateTest {
    private fun notice(title: Int) = SettingsEffect.Notice(title, 2, SettingsNoticeTone.Info)

    @Test
    fun `a notice raised while no screen shows waits until acknowledged`() {
        val state = SettingsNoticeState()
        val refreshed = notice(1)

        state.post(refreshed)

        assertEquals(refreshed, state.latest.value)
        state.acknowledge(refreshed)
        assertNull(state.latest.value)
    }

    @Test
    fun `acknowledging an old notice keeps a newer one`() {
        val state = SettingsNoticeState()
        val old = notice(1)
        val newer = notice(2)
        state.post(old)
        state.post(newer)

        state.acknowledge(old)

        assertEquals(newer, state.latest.value)
    }
}
