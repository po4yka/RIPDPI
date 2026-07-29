package com.poyka.ripdpi.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager

internal class RecoveryUserUnlockReceiverLifecycle(
    private val context: Context,
    private val generationProvider: () -> String?,
    private val onUnlocked: (String) -> Unit,
    private val currentUserUnlocked: () -> Boolean? = {
        runCatching { context.getSystemService(UserManager::class.java)?.isUserUnlocked }
            .getOrNull()
    },
) : AutoCloseable {
    private var receiver: BroadcastReceiver? = null

    fun observeIfLocked(userUnlocked: Boolean?) {
        if (userUnlocked != false || receiver != null) return
        val candidate =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                        recordUnlockedAndClose()
                    }
                }
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                candidate,
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            context.registerReceiver(candidate, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        receiver = candidate
        if (currentUserUnlocked() == true) {
            recordUnlockedAndClose()
        }
    }

    override fun close() {
        val registered = receiver ?: return
        runCatching { context.unregisterReceiver(registered) }
        receiver = null
    }

    private fun recordUnlockedAndClose() {
        generationProvider()?.let(onUnlocked)
        close()
    }
}
