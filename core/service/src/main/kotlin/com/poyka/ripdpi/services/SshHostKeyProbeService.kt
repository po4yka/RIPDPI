package com.poyka.ripdpi.services

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

/** Bound only for credential-free key observation. Never establishes a TUN or starts a runtime. */
class SshHostKeyProbeService : VpnService() {
    private val revoked = AtomicBoolean(false)
    private val binder = ProbeBinder(this)

    override fun onBind(intent: Intent?): IBinder? = binder.takeIf { intent?.action == BindAction && isPrepared() }

    internal fun isPrepared(): Boolean = !revoked.get() && prepare(this) == null

    override fun onRevoke() {
        revoked.set(true)
        super.onRevoke()
    }

    override fun onDestroy() {
        revoked.set(true)
        super.onDestroy()
    }

    internal class ProbeBinder(
        val service: SshHostKeyProbeService,
    ) : Binder()

    internal companion object {
        const val BindAction = "com.poyka.ripdpi.SSH_HOST_KEY_PROBE"
    }
}
