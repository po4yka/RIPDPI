package com.poyka.ripdpi.services

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import com.poyka.ripdpi.service.session.vpn.vpnProtectSocketPath
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real Android UDS wire probe; protection is delegated to the active VPN owner. */
class ProtectSocketOwnershipProbe {
    data class Result(
        val distinctPaths: Boolean,
        val replacementAckBeforeCleanup: Int,
        val replacementAckAfterCleanup: Int?,
        val noDescriptorAck: Int?,
        val successfulProtectCalls: Int,
        val failedProtectCalls: Int,
        val replacementPathPresent: Boolean,
        val connectFailureType: String?,
        val duplicateBindRejected: Boolean,
        val ownerAckAfterFailedBind: Int?,
    )

    fun run(
        filesDir: File,
        provider: ActiveProtectSocketPathProvider,
    ): Result {
        val controller = checkNotNull(provider.captureDirectProtection()) { "Real VPN protection is required" }
        val probeDir = File(filesDir, "uds-test").apply { check(mkdirs() || isDirectory) }
        val successes = AtomicInteger()
        val failures = AtomicInteger()

        fun server(path: String = vpnProtectSocketPath(probeDir)): VpnProtectSocketServer =
            VpnProtectSocketServer(
                path,
                InMemoryVpnProtectFailureMonitor(),
                { fd ->
                    controller.protect(fd).also { if (it) successes.incrementAndGet() else failures.incrementAndGet() }
                },
            )
        val old = server()
        val replacement = server()
        try {
            old.start()
            replacement.start()
            val before = request(replacement.socketPath, includeDescriptor = true)
            old.stop()
            old.stop()
            val after = runCatching { request(replacement.socketPath, includeDescriptor = true) }
            val noDescriptor = runCatching { request(replacement.socketPath, includeDescriptor = false) }
            val duplicate = server(replacement.socketPath)
            val duplicateFailure = runCatching { duplicate.start() }.exceptionOrNull()
            duplicate.stop()
            duplicate.stop()
            val ownerAfterFailedBind = runCatching { request(replacement.socketPath, includeDescriptor = true) }
            return Result(
                old.socketPath != replacement.socketPath,
                before,
                after.getOrNull(),
                noDescriptor.getOrNull(),
                successes.get(),
                failures.get(),
                File(replacement.socketPath).exists(),
                after.exceptionOrNull()?.javaClass?.simpleName,
                duplicateFailure is java.io.IOException,
                ownerAfterFailedBind.getOrNull(),
            )
        } finally {
            old.stop()
            replacement.stop()
            probeDir.delete()
        }
    }

    private fun request(
        path: String,
        includeDescriptor: Boolean,
    ): Int {
        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        try {
            return LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = 2_000
                if (includeDescriptor) socket.setFileDescriptorsForSend(arrayOf(fd))
                socket.outputStream.write(0)
                socket.outputStream.flush()
                socket.inputStream.read()
            }
        } finally {
            Os.close(fd)
        }
    }
}
