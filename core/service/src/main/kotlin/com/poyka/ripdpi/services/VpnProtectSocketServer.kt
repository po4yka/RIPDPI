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
 * upstream socket fd, and awaits a 1-byte ack -- allowing upstream connections to bypass the TUN
 * device.
 *
 * [LocalServerSocket] with a plain name uses the abstract namespace. To use the filesystem
 * namespace (required for native [UnixStream::connect(path)]), we bind a [LocalSocket] using
 * [LocalSocketAddress.Namespace.FILESYSTEM], then call [Os.listen] on its fd and hand that fd
 * to [LocalServerSocket(FileDescriptor)].
 */
internal class VpnProtectSocketServer(
    private val socketPath: String,
    private val protectFailureMonitor: VpnProtectFailureMonitor,
    private val fdProtector: (Int) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val beforeProtectAncillaryFds: () -> Unit = {},
    private val fileDescriptorIntExtractor: ProtectSocketFileDescriptorIntExtractor =
        ReflectiveProtectSocketFileDescriptorIntExtractor,
    handlerConcurrency: Int = DEFAULT_HANDLER_CONCURRENCY,
    maxPendingSessions: Int = DEFAULT_MAX_PENDING_SESSIONS,
    handlerJoinTimeoutMs: Long = DEFAULT_HANDLER_JOIN_TIMEOUT_MS,
) {
    constructor(
        vpnService: VpnService,
        socketPath: String,
        protectFailureMonitor: VpnProtectFailureMonitor,
        clock: () -> Long = System::currentTimeMillis,
        beforeProtectAncillaryFds: () -> Unit = {},
    ) : this(
        socketPath = socketPath,
        protectFailureMonitor = protectFailureMonitor,
        fdProtector = vpnService::protect,
        clock = clock,
        beforeProtectAncillaryFds = beforeProtectAncillaryFds,
    )

    private companion object {
        private val log = Logger.withTag("ProtectSocket")
        private const val LISTEN_BACKLOG = 5
        private const val ACCEPT_THREAD_JOIN_TIMEOUT_MS = 500L
        private const val DEFAULT_HANDLER_CONCURRENCY = 2
        private const val DEFAULT_MAX_PENDING_SESSIONS = 4
        private const val DEFAULT_HANDLER_JOIN_TIMEOUT_MS = 1_000L
    }

    private val sessionDispatcher =
        ProtectSocketSessionDispatcher(
            handlerConcurrency = handlerConcurrency,
            maxPendingSessions = maxPendingSessions,
            joinTimeoutMs = handlerJoinTimeoutMs,
        )
    private val fdProtection =
        ProtectSocketFdProtector(
            protectFailureMonitor = protectFailureMonitor,
            fdProtector = fdProtector,
            clock = clock,
            beforeProtectAncillaryFds = beforeProtectAncillaryFds,
            fileDescriptorIntExtractor = fileDescriptorIntExtractor,
        )

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
                            if (!dispatchClientSession(LocalSocketClientSession(client))) {
                                log.w { "protect socket rejected client due to shutdown or back-pressure" }
                            }
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

    internal fun dispatchClientSession(session: ProtectSocketClientSession): Boolean =
        sessionDispatcher.submit(session, ::handleClientSession)

    internal fun handleClientSession(session: ProtectSocketClientSession) {
        try {
            session.use { client ->
                val bytesRead = client.readHandshake()
                if (bytesRead <= 0) return
                val allProtected = fdProtection.protectAncillaryFds(client)
                client.writeAck(success = allProtected)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.w(e) { "protect socket handler interrupted" }
        } catch (e: IOException) {
            log.w(e) { "protect socket handle error" }
        }
    }

    fun stop() {
        running = false

        val acceptThread = thread
        thread = null

        runCatching { serverSocket?.close() }
        runCatching { bindSocket?.close() }
        serverSocket = null
        bindSocket = null

        acceptThread?.interrupt()
        joinAcceptThread(acceptThread)
        sessionDispatcher.shutdown()

        File(socketPath).delete()
        log.i { "stopped" }
    }

    private fun joinAcceptThread(acceptThread: Thread?) {
        if (acceptThread == null) return
        try {
            acceptThread.join(ACCEPT_THREAD_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
