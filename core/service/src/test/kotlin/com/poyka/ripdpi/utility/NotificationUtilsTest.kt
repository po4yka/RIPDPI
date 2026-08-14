package com.poyka.ripdpi.utility

import android.app.Service
import com.poyka.ripdpi.core.service.R
import com.poyka.ripdpi.services.notificationStopAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationUtilsTest {
    @Test
    fun `static vpn notification hides stop action during lockdown`() {
        val context = RuntimeEnvironment.getApplication()

        val hiddenNotification =
            createConnectionNotification(
                context = context,
                channelId = "test",
                title = R.string.notification_title,
                content = R.string.vpn_notification_content,
                service = Service::class.java,
                showStopAction = false,
            )
        val visibleNotification =
            createConnectionNotification(
                context = context,
                channelId = "test",
                title = R.string.notification_title,
                content = R.string.vpn_notification_content,
                service = Service::class.java,
                showStopAction = true,
            )

        assertEquals(0, hiddenNotification.actions?.size ?: 0)
        assertEquals(1, visibleNotification.actions?.size ?: 0)
        assertEquals(
            notificationStopAction,
            shadowOf(visibleNotification.actions.single().actionIntent).savedIntent.action,
        )
    }

    @Test
    fun `dynamic vpn notification exposes stop action when disconnect is allowed`() {
        val context = RuntimeEnvironment.getApplication()

        val visibleNotification =
            createDynamicConnectionNotification(
                context = context,
                channelId = "test",
                title = "RIPDPI",
                content = "Connected",
                subText = null,
                service = Service::class.java,
                whenTimestamp = 1L,
                showStopAction = true,
            )
        val hiddenNotification =
            createDynamicConnectionNotification(
                context = context,
                channelId = "test",
                title = "RIPDPI",
                content = "Connected",
                subText = null,
                service = Service::class.java,
                whenTimestamp = 1L,
                showStopAction = false,
            )

        assertEquals(1, visibleNotification.actions?.size ?: 0)
        assertEquals(0, hiddenNotification.actions?.size ?: 0)
    }
}
