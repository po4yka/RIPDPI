package com.poyka.ripdpi.services

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val BootResumeTimeoutMillis = 8_000L

internal fun launchBoundedBootResume(
    scope: CoroutineScope,
    timeoutMillis: Long,
    resume: suspend () -> Unit,
    onFailure: (Throwable) -> Unit,
    finish: () -> Unit,
): Job {
    val finished = AtomicBoolean(false)
    val finishOnce = { if (finished.compareAndSet(false, true)) finish() }
    val job =
        scope.launch {
            try {
                withTimeout(timeoutMillis) { resume() }
            } catch (timeout: TimeoutCancellationException) {
                onFailure(timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                onFailure(error)
            } finally {
                finishOnce()
            }
        }
    job.invokeOnCompletion { finishOnce() }
    return job
}

/**
 * Resumes the previously-active RIPDPI session after the device boots or the app
 * is updated. Handles `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` after credential
 * storage is available.
 *
 * Declared `android:enabled="false"` and toggled on only while the user's
 * "Start on boot" preference is enabled — see [setEnabled]. `onReceive` does no
 * The receiver retains the boot broadcast with [goAsync] while [BootResumeCoordinator]
 * reads the session pointer and starts the foreground service. Keeping the start inside
 * the broadcast lifetime preserves Android's boot foreground-service exemption.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var bootResumeCoordinator: BootResumeCoordinator

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Logger.withTag("BootReceiver").i { "received $action; resuming within broadcast lifetime" }
                val pendingResult = goAsync()
                launchBoundedBootResume(
                    scope = applicationScope,
                    timeoutMillis = BootResumeTimeoutMillis,
                    resume = { bootResumeCoordinator.resume(action) },
                    onFailure = { error ->
                        Logger.withTag("BootReceiver").e(error) { "boot resume failed for $action" }
                    },
                    finish = pendingResult::finish,
                )
            }

            else -> {
            }
        }
    }

    companion object {
        /**
         * Enables or disables the [BootReceiver] component at runtime, wired to the
         * "Start on boot" Settings toggle. `DONT_KILL_APP` keeps the process alive
         * while the component state is flipped. Disabling removes the broadcast
         * filter entirely so the receiver does not keep the package warm when unused.
         */
        fun setEnabled(
            context: Context,
            enabled: Boolean,
        ) {
            val state =
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BootReceiver::class.java),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
