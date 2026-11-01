package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsSerializer
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

    @Test
    fun `the settings notice follows the persisted reset flag`() {
        val pending =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setAppLockResetNoticePending(true)
                .build()

        val notice = requireNotNull(pending.settingsNotice())
        assertEquals(R.string.notice_pin_key_lost_title, notice.title)
        assertEquals(R.string.notice_pin_key_lost_message, notice.message)
        assertEquals(SettingsNoticeTone.Warning, notice.tone)
        assertNull(
            pending
                .toBuilder()
                .setAppLockResetNoticePending(false)
                .build()
                .settingsNotice(),
        )
    }
}
