package com.poyka.ripdpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `status channel stays quiet and describes itself`() {
        val context = RuntimeEnvironment.getApplication()
        val target = ConnectionNotificationTarget.Vpn

        registerConnectionChannel(context, target.channelId, target.channelName)

        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(target.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Shows connection status", channel.description)
        val notification = notification(startedAt = null, showStopAction = false)
        assertEquals(Notification.CATEGORY_SERVICE, notification.category)
    }

    @Test
    fun `telemetry maps to the state a user needs`() {
        val traffic = TunnelStats(txBytes = 1L, rxBytes = 2L)
        val running = ServiceTelemetrySnapshot(serviceStartedAt = 7L)

        assertNull(connectionNotificationPhase(ServiceTelemetrySnapshot(), traffic))
        assertEquals(
            ConnectionNotificationPhase.Connected(warning = false, traffic = traffic, startedAt = 7L),
            connectionNotificationPhase(running, traffic),
        )
        assertEquals(
            ConnectionNotificationPhase.Connected(warning = true, traffic = traffic, startedAt = 7L),
            connectionNotificationPhase(running.copy(relayFailed = true), traffic),
        )
        assertEquals(
            ConnectionNotificationPhase.Reconnecting,
            connectionNotificationPhase(
                running.copy(networkHandoverState = NetworkHandoverStates.Restarting),
                traffic,
            ),
        )
        assertEquals(
            ConnectionNotificationPhase.Connected(warning = false, traffic = traffic, startedAt = 7L),
            connectionNotificationPhase(
                running.copy(networkHandoverState = NetworkHandoverStates.Revalidated),
                traffic,
            ),
        )
    }

    @Test
    fun `connected state names the mode and shows one timer`() {
        val context = RuntimeEnvironment.getApplication()
        val connected = ConnectionNotificationPhase.Connected(false, TunnelStats(), startedAt = 7L)

        val vpn = connectionNotificationContent(context, ConnectionNotificationTarget.Vpn, connected)
        val proxy =
            connectionNotificationContent(
                context,
                ConnectionNotificationTarget.Proxy,
                connected.copy(warning = true),
            )
        val connecting =
            connectionNotificationContent(
                context,
                ConnectionNotificationTarget.Vpn,
                ConnectionNotificationPhase.Connecting,
            )

        assertEquals("Connected · VPN", vpn.title)
        assertEquals(7L, vpn.startedAt)
        assertEquals("Connected · Proxy · warning", proxy.title)
        assertEquals("Connecting…", connecting.title)
        assertNull(connecting.text)
        assertNull(connecting.startedAt)
    }

    @Test
    fun `failure alert survives its service and clears on the next connection`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))

        showConnectionFailureAlert(context, appVisible = true)
        assertTrue(manager.allNotifications.isEmpty())

        showConnectionFailureAlert(context, appVisible = false)

        val alert = manager.allNotifications.single()
        assertEquals("Connection lost", alert.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(alert.flags and Notification.FLAG_AUTO_CANCEL != 0)

        cancelConnectionFailureAlert(context)
        assertTrue(manager.allNotifications.isEmpty())
    }

    @Test
    fun `alert skips failures that follow a user action`() {
        assertFalse(RuntimeCleanupIncompleteFailure.warrantsConnectionAlert())
        assertFalse(FailureReason.PermissionLost("VPN").warrantsConnectionAlert())
        assertTrue(FailureReason.TunnelEstablishmentFailed.warrantsConnectionAlert())
        assertTrue(FailureReason.PermissionLost(LocalNetworkPermission).warrantsConnectionAlert())
    }

    @Test
    fun `traffic names both directions without symbols`() {
        val context = RuntimeEnvironment.getApplication()

        assertEquals("0 B received · 500 B sent", connectionTrafficText(context, 500L, 0L))
        assertEquals("1.5 GB received · 1.5 MB sent", connectionTrafficText(context, 1_500_000L, 1_500_000_000L))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `traffic uses the app locale`() {
        val text = connectionTrafficText(RuntimeEnvironment.getApplication(), 1_500_000L, 1_500_000_000L)

        assertEquals("Получено 1,5 ГБ · отправлено 1,5 МБ", text)
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
