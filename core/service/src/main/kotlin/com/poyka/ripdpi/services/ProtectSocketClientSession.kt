package com.poyka.ripdpi.services

import android.net.LocalSocket
import java.io.FileDescriptor
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

internal interface ProtectSocketClientSession : AutoCloseable {
    val ancillaryFileDescriptors: Array<FileDescriptor>?

    fun readHandshake(): Int

    fun writeAck(success: Boolean)
}

internal class LocalSocketClientSession(
    private val socket: LocalSocket,
) : ProtectSocketClientSession {
    private val handshakeDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)

    override val ancillaryFileDescriptors: Array<FileDescriptor>?
        get() = socket.ancillaryFileDescriptors

    override fun readHandshake(): Int {
        val remainingNanos = handshakeDeadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) throw SocketTimeoutException("Protect socket handshake expired")
        socket.soTimeout =
            TimeUnit.NANOSECONDS
                .toMillis(remainingNanos)
                .coerceAtLeast(1L)
                .toInt()
        return socket.inputStream.read(ByteArray(1))
    }

    override fun writeAck(success: Boolean) {
        socket.outputStream.write(byteArrayOf(if (success) 0 else 1))
        socket.outputStream.flush()
    }

    override fun close() {
        // Wakes a handler blocked in read(); LocalSocket.close() alone does not.
        runCatching { socket.shutdownInput() }
        socket.close()
    }
}
