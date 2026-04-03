package com.poyka.ripdpi.services

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.system.Os
import co.touchlab.kermit.Logger
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
) {
    private companion object {
        private val log = Logger.withTag("ProtectSocket")
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
        Os.listen(fd, 5)

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
                val input = socket.inputStream
                val output = socket.outputStream

                val buf = ByteArray(1)
                val bytesRead = input.read(buf)
                if (bytesRead <= 0) return

                val fds: Array<FileDescriptor>? = socket.ancillaryFileDescriptors
                if (fds != null) {
                    for (fd in fds) {
                        val fdInt = extractFdInt(fd)
                        if (fdInt >= 0) {
                            vpnService.protect(fdInt)
                            log.d { "protected fd=$fdInt" }
                        }
                        runCatching { Os.close(fd) }
                    }
                }

                output.write(byteArrayOf(0))
                output.flush()
            }
        } catch (e: Exception) {
            log.w(e) { "protect socket handle error" }
        }
    }

    @Suppress("DiscouragedPrivateApi")
    private fun extractFdInt(fd: FileDescriptor): Int =
        try {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.getInt(fd)
        } catch (e: Exception) {
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
