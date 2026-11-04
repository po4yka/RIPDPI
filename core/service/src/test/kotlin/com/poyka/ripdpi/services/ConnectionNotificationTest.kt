package com.poyka.ripdpi.services

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionNotificationTest {
    @Test
    fun `stop action follows the disconnect policy in every state`() {
        listOf(null, 1L).forEach { startedAt ->
            val hidden = notification(startedAt = startedAt, showStopAction = false)
            val visible = notification(startedAt = startedAt, showStopAction = true)

            assertEquals(0, hidden.actions?.size ?: 0)
            assertEquals(1, visible.actions?.size ?: 0)
            assertEquals(
                notificationStopAction,
                shadowOf(visible.actions.single().actionIntent).savedIntent.action,
            )
        }
    }

    private fun notification(
        startedAt: Long?,
        showStopAction: Boolean,
    ) = createConnectionNotification(
        context = RuntimeEnvironment.getApplication(),
        channelId = "test",
        title = "RIPDPI",
        content = "Connected",
        service = Service::class.java,
        startedAt = startedAt,
        showStopAction = showStopAction,
    )
}
