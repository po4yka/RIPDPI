package com.poyka.ripdpi.services

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class HardKillSwitchRefreshBroadcastLifecycleTest {
    @Test
    fun `queued refresh cannot call destroyed owner before asynchronous unregister`() {
        val application: Application = RuntimeEnvironment.getApplication()
        val owner = CoroutineScope(Job())
        val operations = mutableListOf<String>()
        val lifecycle =
            HardKillSwitchRefreshBroadcastLifecycle(
                application,
                owner,
                { operations += "state" },
                { operations += "notification" },
            )
        lifecycle.start()
        application.sendBroadcast(Intent(hardKillSwitchRefreshBroadcastAction).setPackage(application.packageName))
        owner.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), operations)
        lifecycle.close()
    }

    @Test
    fun `receiver uses application registration when transient service context is destroyed`() {
        val application: Application = RuntimeEnvironment.getApplication()
        val serviceContext =
            object : ContextWrapper(application) {
                override fun registerReceiver(
                    receiver: BroadcastReceiver?,
                    filter: IntentFilter,
                    broadcastPermission: String?,
                    scheduler: Handler?,
                    flags: Int,
                ): Intent? = error("Service receiver registration no longer has a live context")
            }
        val operations = mutableListOf<String>()
        val lifecycle =
            HardKillSwitchRefreshBroadcastLifecycle(
                serviceContext,
                CoroutineScope(Job()),
                { operations += "state" },
                { operations += "notification" },
            )
        lifecycle.start()
        application.sendBroadcast(Intent(hardKillSwitchRefreshBroadcastAction).setPackage(application.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("state", "notification"), operations)
        lifecycle.close()
    }

    @Test
    fun `package broadcast refreshes in order only while lifecycle is registered`() {
        val context: Application = RuntimeEnvironment.getApplication()
        val operations = mutableListOf<String>()
        val lifecycle =
            HardKillSwitchRefreshBroadcastLifecycle(
                context = context,
                ownerScope = CoroutineScope(Job()),
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
}
