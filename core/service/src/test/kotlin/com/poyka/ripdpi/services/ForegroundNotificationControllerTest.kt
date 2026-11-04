package com.poyka.ripdpi.services

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.poyka.ripdpi.data.PauseIntent
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.startAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForegroundNotificationControllerTest {
    private val service: Service = Robolectric.buildService(FixtureService::class.java).create().get()
    private var telemetry = ServiceTelemetrySnapshot(serviceStartedAt = 7L)
    private var paused: PauseIntent? = null
    private val controller =
        ForegroundNotificationController(
            service = service,
            target = ConnectionNotificationTarget.Vpn,
            telemetry = { telemetry },
            pauseIntent = { paused },
        )

    private val posted: Notification?
        get() =
            shadowOf(service.getSystemService(android.app.NotificationManager::class.java))
                .getNotification(ConnectionNotificationTarget.Vpn.notificationId)

    private val title: String?
        get() = posted?.extras?.getString(Notification.EXTRA_TITLE)

    @Test
    fun `sticky redelivery reports reconnecting and a fresh start reports connecting`() {
        controller.startForeground(null)
        assertEquals("Reconnecting…", shadowOf(service).lastForegroundNotification.title())

        controller.startForeground(startAction)
        assertEquals("Connecting…", shadowOf(service).lastForegroundNotification.title())
    }

    @Test
    fun `a repeated command and a policy refresh keep live state`() {
        controller.startForeground(startAction)
        controller.update(TunnelStats(), interactive = true)

        controller.startForeground(startAction)
        assertEquals("Connected · VPN", shadowOf(service).lastForegroundNotification.title())

        controller.refresh()
        assertEquals("Connected · VPN", shadowOf(service).lastForegroundNotification.title())
    }

    @Test
    fun `screen off skips traffic refreshes but publishes state changes`() {
        controller.startForeground(startAction)
        controller.update(TunnelStats(txBytes = 1L), interactive = false)
        assertEquals("Connected · VPN", title)
        val published = posted

        controller.update(TunnelStats(txBytes = 2L), interactive = false)
        assertTrue(published === posted)

        telemetry = telemetry.copy(relayFailed = true)
        controller.update(TunnelStats(txBytes = 3L), interactive = false)
        assertEquals("Connected · VPN · warning", title)
    }

    @Test
    fun `stop shows stopping without a stop action and telemetry returns only after a rejected stop`() {
        controller.startForeground(startAction)
        controller.update(TunnelStats(), interactive = true)

        controller.startForeground(notificationStopAction)
        val stopping = shadowOf(service).lastForegroundNotification
        assertEquals("Stopping…", stopping.title())
        assertEquals(0, stopping.actions?.size ?: 0)

        controller.update(TunnelStats(), interactive = true)
        assertEquals("Stopping…", shadowOf(service).lastForegroundNotification.title())
        assertTrue(title != "Connected · VPN")

        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        controller.update(TunnelStats(), interactive = true)
        assertEquals("Connected · VPN", title)
    }

    @Test
    fun `telemetry cannot overwrite a paused notification`() {
        paused = pauseIntent()
        controller.showPaused(paused!!)
        val pausedTitle = shadowOf(service).lastForegroundNotification.title()

        controller.update(TunnelStats(), interactive = true)
        controller.startForeground(startAction)

        assertEquals(pausedTitle, shadowOf(service).lastForegroundNotification.title())
        assertTrue(title != "Connected · VPN")
    }

    private fun Notification.title() = extras.getString(Notification.EXTRA_TITLE)

    private fun pauseIntent() =
        PauseIntent(
            generation = 1L,
            token = "fixture",
            mode = "vpn",
            createdWallMillis = 0L,
            createdElapsedMillis = 0L,
            deadlineWallMillis = 60_000L,
            deadlineElapsedMillis = 60_000L,
            bootCount = 1,
            phase = PausePhase.Paused,
            failure = null,
        )

    class FixtureService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }
}
