package com.poyka.ripdpi.services

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class HardKillSwitchRefreshBroadcastLifecycleTest {
    @Test
    fun `session receiver can close after its service context is destroyed`() {
        val application: Application = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(ReceiverHostService::class.java).create()
        val operations = mutableListOf<String>()
        val lifecycle =
            HardKillSwitchRefreshBroadcastLifecycle(
                context = service.get(),
                onRefreshState = { operations += "state" },
                onRefreshNotification = { operations += "notification" },
            )
        lifecycle.start()
        val registration =
            shadowOf(application).registeredReceivers.single {
                it.intentFilter.hasAction(hardKillSwitchRefreshBroadcastAction)
            }
        assertSame(application, registration.context)

        service.destroy()
        lifecycle.close()
        lifecycle.close()
        application.sendBroadcast(
            Intent(hardKillSwitchRefreshBroadcastAction).setPackage(application.packageName),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), operations)
    }

    @Test
    fun `package broadcast refreshes in order only while lifecycle is registered`() {
        val context: Application = RuntimeEnvironment.getApplication()
        val operations = mutableListOf<String>()
        val lifecycle =
            HardKillSwitchRefreshBroadcastLifecycle(
                context = context,
                onRefreshState = { operations += "state" },
                onRefreshNotification = { operations += "notification" },
            )
        lifecycle.start()

        context.sendBroadcast(
            Intent(hardKillSwitchRefreshBroadcastAction).setPackage("com.example.other"),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), operations)

        val refreshIntent =
            Intent(hardKillSwitchRefreshBroadcastAction).setPackage(context.packageName)
        context.sendBroadcast(refreshIntent)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("state", "notification"), operations)

        lifecycle.close()
        context.sendBroadcast(refreshIntent)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("state", "notification"), operations)
    }

    class ReceiverHostService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }
}
