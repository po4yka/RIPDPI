package com.poyka.ripdpi.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Enabled for a pending pause independently from the user's standing start-on-boot preference. */
@AndroidEntryPoint
class PauseBootReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TimedPauseController

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Exported for system broadcasts: ignore any other action an app sends.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { controller.restoreAfterBoot(intent.action) }.onFailure { failure ->
                    if (failure !is Exception || failure is kotlinx.coroutines.CancellationException) throw failure
                    co.touchlab.kermit.Logger
                        .e { "Pause boot reconstruction failed: ${failure::class.java.simpleName}" }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
