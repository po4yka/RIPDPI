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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
                val allProtected = protectAncillaryFds(client)
                client.writeAck(success = allProtected)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.w(e) { "protect socket handler interrupted" }
        } catch (e: IOException) {
            log.w(e) { "protect socket handle error" }
        }
    }

    private fun protectAncillaryFds(session: ProtectSocketClientSession): Boolean {
        val fds = session.ancillaryFileDescriptors.orEmpty()
        if (fds.isEmpty()) return true

        beforeProtectAncillaryFds()

        var allProtected = true
        for (fd in fds) {
            when (val extracted = fileDescriptorIntExtractor.extract(fd)) {
                is ProtectSocketFdExtractionResult.Extracted -> {
                    if (!protectFd(extracted.value)) {
                        allProtected = false
                    }
                }

                is ProtectSocketFdExtractionResult.Failed -> {
                    reportProtectFailure(
                        fd = -1,
                        reason = FailureReason.NativeError(extracted.error.detail),
                        detail = extracted.error.detail,
                    )
                    allProtected = false
                }
            }
            runCatching { Os.close(fd) }
        }
        return allProtected
    }

    private fun protectFd(fdInt: Int): Boolean {
        val protectResult =
            runCatching { fdProtector(fdInt) }
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
                return true
            }

            is ProtectResult.Rejected -> {
                reportProtectFailure(
                    fd = fdInt,
                    reason = FailureReason.PermissionLost("VPN"),
                    detail = protectResult.detail,
                )
            }

            is ProtectResult.Failed -> {
                reportProtectFailure(
                    fd = fdInt,
                    reason = protectResult.reason,
                    detail = protectResult.detail,
                )
            }
        }
        return false
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

private class ProtectSocketSessionDispatcher(
    handlerConcurrency: Int,
    maxPendingSessions: Int,
    private val joinTimeoutMs: Long,
) {
    private val closed = AtomicBoolean(false)
    private val permits: Semaphore
    private val activeTasks = ConcurrentHashMap.newKeySet<ProtectSocketHandlerTask>()
    private val executor: ThreadPoolExecutor

    init {
        require(handlerConcurrency > 0) { "handlerConcurrency must be > 0" }
        require(maxPendingSessions >= 0) { "maxPendingSessions must be >= 0" }
        require(joinTimeoutMs >= 0L) { "joinTimeoutMs must be >= 0" }

        permits = Semaphore(handlerConcurrency + maxPendingSessions)
        executor =
            ThreadPoolExecutor(
                handlerConcurrency,
                handlerConcurrency,
                0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(),
                ProtectSocketThreadFactory(),
            )
    }

    fun submit(
        session: ProtectSocketClientSession,
        handler: (ProtectSocketClientSession) -> Unit,
    ): Boolean {
        if (closed.get()) {
            rejectSession(session)
            return false
        }

        if (!permits.tryAcquire()) {
            rejectSession(session)
            return false
        }

        if (closed.get()) {
            permits.release()
            rejectSession(session)
            return false
        }

        val task = ProtectSocketHandlerTask(session, permits, handler, activeTasks)
        activeTasks += task
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            activeTasks -= task
            permits.release()
            rejectSession(session)
            false
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(joinTimeoutMs)
        closeActiveSessions()
        executor.shutdown()
        if (awaitTerminationBefore(deadlineNanos)) return

        executor
            .shutdownNow()
            .filterIsInstance<ProtectSocketHandlerTask>()
            .forEach(ProtectSocketHandlerTask::closeSessionQuietly)
        closeActiveSessions()
        awaitTerminationBefore(deadlineNanos)
    }

    private fun awaitTerminationBefore(deadlineNanos: Long): Boolean {
        while (true) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return executor.isTerminated
            try {
                return executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return executor.isTerminated
            }
        }
    }

    private fun closeActiveSessions() {
        activeTasks.forEach(ProtectSocketHandlerTask::closeSessionQuietly)
    }

    private fun rejectSession(session: ProtectSocketClientSession) {
        runCatching {
            session.use {
                it.writeAck(success = false)
            }
        }
    }
}

private class ProtectSocketHandlerTask(
    private val session: ProtectSocketClientSession,
    private val permits: Semaphore,
    private val handler: (ProtectSocketClientSession) -> Unit,
    private val activeTasks: MutableSet<ProtectSocketHandlerTask>,
) : Runnable {
    override fun run() {
        try {
            handler(session)
        } finally {
            permits.release()
            activeTasks.remove(this)
        }
    }

    fun closeSessionQuietly() {
        runCatching { session.close() }
    }
}

private class ProtectSocketThreadFactory : ThreadFactory {
    private val index = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "vpn-protect-handler-${index.incrementAndGet()}").apply {
            isDaemon = true
        }
}

internal interface ProtectSocketClientSession : AutoCloseable {
    val ancillaryFileDescriptors: Array<FileDescriptor>?

    fun readHandshake(): Int

    fun writeAck(success: Boolean)
}

private class LocalSocketClientSession(
    private val socket: LocalSocket,
) : ProtectSocketClientSession {
    override val ancillaryFileDescriptors: Array<FileDescriptor>?
        get() = socket.ancillaryFileDescriptors

    override fun readHandshake(): Int = socket.inputStream.read(ByteArray(1))

    override fun writeAck(success: Boolean) {
        socket.outputStream.write(byteArrayOf(if (success) 0 else 1))
        socket.outputStream.flush()
    }

    override fun close() {
        socket.close()
    }
}
