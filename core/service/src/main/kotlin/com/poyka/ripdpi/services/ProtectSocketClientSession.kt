package com.poyka.ripdpi.services

import android.net.LocalSocket
import java.io.FileDescriptor

internal interface ProtectSocketClientSession : AutoCloseable {
    val ancillaryFileDescriptors: Array<FileDescriptor>?

    fun readHandshake(): Int

    fun writeAck(success: Boolean)
}

internal class LocalSocketClientSession(
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
