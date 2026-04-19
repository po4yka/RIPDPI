package com.poyka.ripdpi.services

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.system.Os
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.FailureReason
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/**
 * Listens on a Unix domain socket (filesystem namespace) and calls [VpnService.protect] on any
 * file descriptor received via SCM_RIGHTS ancillary data.
 *
 * The native Rust proxy connects to this socket via [protect_socket()][socketPath], sends the
 * upstream socket fd, and awaits a 1-byte ack — allowing upstream connections to bypass the TUN
 * device.
 *
 * [LocalServerSocket] with a plain name uses the abstract namespace. To use the filesystem
 * namespace (required for native [UnixStream::connect(path)]), we bind a [LocalSocket] using
 * [LocalSocketAddress.Namespace.FILESYSTEM], then call [Os.listen] on its fd and hand that fd
 * to [LocalServerSocket(FileDescriptor)].
 */
internal class VpnProtectSocketServer(
    private val vpnService: VpnService,
    private val socketPath: String,
    private val protectFailureMonitor: VpnProtectFailureMonitor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        private val log = Logger.withTag("ProtectSocket")
        private const val LISTEN_BACKLOG = 5
    }

    @Volatile private var serverSocket: LocalServerSocket? = null

    @Volatile private var bindSocket: LocalSocket? = null

    @Volatile private var running = false

    @Volatile private var thread: Thread? = null

    fun start() {
        File(socketPath).delete()

        val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
        bound.bind(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
        bindSocket = bound

        val fd: FileDescriptor = bound.fileDescriptor
        Os.listen(fd, LISTEN_BACKLOG)

        val server = LocalServerSocket(fd)
        serverSocket = server
        running = true
        log.i { "listening at $socketPath" }

        thread =
            Thread(
                {
                    while (running) {
                        try {
                            val client = server.accept()
                            handleClient(client)
                        } catch (e: IOException) {
                            if (running) {
                                log.w(e) { "protect socket accept error" }
                            }
                        }
                    }
                },
                "vpn-protect-socket",
            ).also {
                it.isDaemon = true
                it.start()
            }
    }

    private fun handleClient(client: LocalSocket) {
        try {
            client.use { socket ->
                val bytesRead = socket.inputStream.read(ByteArray(1))
                if (bytesRead <= 0) return
                val allProtected = protectAncillaryFds(socket)
                socket.outputStream.write(byteArrayOf(if (allProtected) 0 else 1))
                socket.outputStream.flush()
            }
        } catch (e: IOException) {
            log.w(e) { "protect socket handle error" }
        }
    }

    private fun protectAncillaryFds(socket: LocalSocket): Boolean {
        val fds: Array<FileDescriptor> = socket.ancillaryFileDescriptors ?: return true
        var allProtected = true
        for (fd in fds) {
            val fdInt = extractFdInt(fd)
            if (fdInt >= 0) {
                val protectResult =
                    runCatching { vpnService.protect(fdInt) }
                        .fold(
                            onSuccess = { protected ->
                                if (protected) {
                                    ProtectResult.Protected
                                } else {
                                    ProtectResult.Rejected("VpnService.protect() returned false")
                                }
                            },
                            onFailure = { error ->
                                ProtectResult.Failed(
                                    reason =
                                        when (error) {
                                            is SecurityException -> {
                                                FailureReason.PermissionLost("VPN")
                                            }

                                            else -> {
                                                FailureReason.NativeError(
                                                    "VpnService.protect() failed for fd=$fdInt: " +
                                                        "${error.message ?: "unknown error"}",
                                                )
                                            }
                                        },
                                    detail =
                                        error.message
                                            ?: "VpnService.protect() threw ${error::class.java.simpleName}",
                                )
                            },
                        )
                when (protectResult) {
                    ProtectResult.Protected -> {
                        log.d { "protected fd=$fdInt" }
                    }

                    is ProtectResult.Rejected -> {
                        reportProtectFailure(
                            fd = fdInt,
                            reason = FailureReason.PermissionLost("VPN"),
                            detail = protectResult.detail,
                        )
                        allProtected = false
                    }

                    is ProtectResult.Failed -> {
                        reportProtectFailure(
                            fd = fdInt,
                            reason = protectResult.reason,
                            detail = protectResult.detail,
                        )
                        allProtected = false
                    }
                }
            }
            runCatching { Os.close(fd) }
        }
        return allProtected
    }

    private fun reportProtectFailure(
        fd: Int,
        reason: FailureReason,
        detail: String,
    ) {
        protectFailureMonitor.report(
            VpnProtectFailureEvent(
                fd = fd,
                reason = reason,
                detail = detail,
                detectedAt = clock(),
            ),
        )
        log.e { "vpn protect failed for fd=$fd: $detail" }
    }

    private sealed interface ProtectResult {
        data object Protected : ProtectResult

        data class Rejected(
            val detail: String,
        ) : ProtectResult

        data class Failed(
            val reason: FailureReason,
            val detail: String,
        ) : ProtectResult
    }

    @Suppress("DiscouragedPrivateApi")
    private fun extractFdInt(fd: FileDescriptor): Int =
        try {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.getInt(fd)
        } catch (e: NoSuchFieldException) {
            log.w(e) { "failed to extract fd int" }
            -1
        } catch (e: IllegalAccessException) {
            log.w(e) { "failed to extract fd int" }
            -1
        }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { bindSocket?.close() }
        serverSocket = null
        bindSocket = null
        thread?.interrupt()
        thread = null
        File(socketPath).delete()
        log.i { "stopped" }
    }
}
