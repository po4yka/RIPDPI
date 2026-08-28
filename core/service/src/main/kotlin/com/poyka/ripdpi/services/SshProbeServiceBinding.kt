package com.poyka.ripdpi.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred

/** Created before dispatching bind to Main, so cancellation cannot lose the unbind owner. */
internal class SshProbeServiceBinding(
    private val context: Context,
    private val lease: SshProbeOperationLease,
) : ServiceConnection {
    private val connected = CompletableDeferred<SshHostKeyProbeService>()
    private var bindAttempted = false
    private var closed = false

    fun bind() {
        check(!closed && lease.isActive()) { "SSH observation revoked" }
        bindAttempted = true
        val intent = Intent(context, SshHostKeyProbeService::class.java).setAction(SshHostKeyProbeService.BindAction)
        check(context.bindService(intent, this, Context.BIND_AUTO_CREATE)) { "SSH observation binding unavailable" }
    }

    suspend fun awaitService(): SshHostKeyProbeService = connected.await()

    override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
    ) {
        val probe = (service as? SshHostKeyProbeService.ProbeBinder)?.service
        if (closed || !lease.isActive() || probe == null) {
            disconnect()
        } else {
            connected.complete(probe)
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) = disconnect()

    override fun onBindingDied(name: ComponentName?) = disconnect()

    override fun onNullBinding(name: ComponentName?) = disconnect()

    private fun disconnect() {
        lease.revoke()
        connected.completeExceptionally(IllegalStateException("SSH observation binding lost"))
    }

    fun close() {
        if (closed) return
        closed = true
        disconnect()
        if (bindAttempted) {
            try {
                context.unbindService(this)
            } catch (_: IllegalArgumentException) {
                // A failed bind can leave no registration. No other connection is touched.
            }
        }
    }
}
