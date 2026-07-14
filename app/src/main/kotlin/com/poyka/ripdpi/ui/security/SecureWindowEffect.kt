package com.poyka.ripdpi.ui.security

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface SecureWindowLease {
    fun release()
}

internal class SecureWindowFlagOwner(
    private val setSecure: (Boolean) -> Unit,
) {
    private var activeLeases: Int = 0

    @Synchronized
    fun acquire(): SecureWindowLease {
        if (activeLeases++ == 0) setSecure(true)
        val released = AtomicBoolean(false)
        return SecureWindowLease {
            if (released.compareAndSet(false, true)) releaseLease()
        }
    }

    @Synchronized
    private fun releaseLease() {
        check(activeLeases > 0) { "Secure window lease underflow" }
        if (--activeLeases == 0) setSecure(false)
    }
}

private object SecureWindowFlagRegistry {
    private val owners = WeakHashMap<Window, SecureWindowFlagOwner>()

    @Synchronized
    fun acquire(window: Window): SecureWindowLease =
        owners
            .getOrPut(window) {
                val windowReference = WeakReference(window)
                SecureWindowFlagOwner { secure ->
                    windowReference.get()?.let { activeWindow ->
                        if (secure) {
                            activeWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            activeWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
            }.acquire()
}

@Composable
internal fun SecureWindowEffect() {
    val window = LocalActivity.current?.window
    val enabled = !System.getProperty("ripdpi.staticMotion").toBoolean()
    DisposableEffect(window, enabled) {
        val lease = window?.takeIf { enabled }?.let(SecureWindowFlagRegistry::acquire)
        onDispose { lease?.release() }
    }
}
