package com.poyka.ripdpi.services

import android.content.Intent
import android.os.Build
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class RecoveryUserUnlockReceiverLifecycleTest {
    @Test
    fun `locked boot records unlock for the current generation once`() {
        val context = RuntimeEnvironment.getApplication()
        val recorded = mutableListOf<String>()
        var generation: String? = "generation-a"
        val lifecycle =
            RecoveryUserUnlockReceiverLifecycle(
                context = context,
                generationProvider = { generation },
                onUnlocked = recorded::add,
                currentUserUnlocked = { false },
            )
        lifecycle.observeIfLocked(userUnlocked = false)
        generation = "generation-b"

        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("generation-b"), recorded)
    }
}
