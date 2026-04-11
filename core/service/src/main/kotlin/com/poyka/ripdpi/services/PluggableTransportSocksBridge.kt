package com.poyka.ripdpi.services

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import kotlin.concurrent.thread

data class ManagedClientSocksBridgeSpec(
    val methodName: String,
    val targetHost: String,
    val targetPort: Int,
    val ptArguments: String,
)

internal fun parseManagedClientListenerLine(
    line: String,
    methodName: String,
): InetSocketAddress? {
    val parts = line.trim().split(' ')
    if (parts.size != 4 || parts[0] != "CMETHOD" || parts[1] != methodName || parts[2] != "socks5") {
        return null
    }
    val delimiter = parts[3].lastIndexOf(':')
    if (delimiter <= 0 || delimiter == parts[3].lastIndex) {
        return null
    }
    val host = parts[3].substring(0, delimiter)
    val port = parts[3].substring(delimiter + 1).toIntOrNull() ?: return null
    return InetSocketAddress(host, port)
}

internal fun splitPtAuthArgs(arguments: String): Pair<ByteArray, ByteArray> {
    val bytes = arguments.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 510) {
        "Pluggable transport arguments exceed SOCKS RFC1929 capacity"
    }
    return if (bytes.size <= 255) {
        bytes to byteArrayOf(0x00)
    } else {
        bytes.copyOfRange(0, 255) to bytes.copyOfRange(255, bytes.size)
    }
}

internal class ManagedClientSocksBridge(
    private val listenHost: String,
    private val listenPort: Int,
    private val upstreamListener: InetSocketAddress,
    private val bridgeSpec: ManagedClientSocksBridgeSpec,
) : Closeable {
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    @Volatile private var serverSocket: ServerSocket? = null

    @Volatile private var acceptThread: Thread? = null

    fun start() {
        val listener =
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(listenHost, listenPort))
            }
        serverSocket = listener
        acceptThread =
            thread(
                name = "pt-socks-bridge-$listenPort",
                isDaemon = true,
            ) {
                acceptLoop(listener)
            }
    }

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        val sockets = synchronized(activeSockets) { activeSockets.toList() }
        sockets.forEach { socket ->
            runCatching { socket.close() }
        }
        activeSockets.clear()
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (!listener.isClosed) {
            val client =
                try {
                    listener.accept()
                } catch (_: SocketException) {
                    return
                }
            activeSockets += client
            thread(
                name = "pt-socks-bridge-client-$listenPort",
                isDaemon = true,
            ) {
                client.use { outer ->
                    try {
                        handleClient(outer)
                    } finally {
                        activeSockets -= outer
                    }
                }
            }
        }
    }

    private fun handleClient(outer: Socket) {
        outer.soTimeout = 5_000
        negotiateOuterHandshake(outer)
        val upstream =
            Socket().apply {
                connect(upstreamListener, 5_000)
                soTimeout = 5_000
            }
        activeSockets += upstream
        upstream.use { inner ->
            try {
                connectUpstream(inner)
                writeOuterReply(outer, replyCode = 0x00)
                pipeBidirectional(outer, inner)
            } catch (error: Throwable) {
                writeOuterReply(outer, replyCode = 0x01)
                if (error is IOException) {
                    throw error
                }
                throw IOException(error)
            } finally {
                activeSockets -= inner
            }
        }
    }

    private fun negotiateOuterHandshake(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        require(readByte(input) == 0x05) { "Unsupported SOCKS version" }
        val methodCount = readByte(input)
        val methods = ByteArray(methodCount)
        readFully(input, methods)
        if (methods.none { it == 0x00.toByte() }) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            error("No supported SOCKS authentication methods")
        }
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        require(readByte(input) == 0x05) { "Unsupported SOCKS request version" }
        val command = readByte(input)
        readByte(input) // reserved
        val atyp = readByte(input)
        skipAddress(input, atyp)
        readU16(input)
        require(command == 0x01) { "Only SOCKS CONNECT is supported" }
    }

    private fun connectUpstream(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val args = bridgeSpec.ptArguments.trim()
        val methods =
            if (args.isNotEmpty()) {
                byteArrayOf(0x05, 0x01, 0x02)
            } else {
                byteArrayOf(0x05, 0x01, 0x00)
            }
        output.write(methods)
        output.flush()
        val greeting = ByteArray(2)
        readFully(input, greeting)
        require(greeting[0] == 0x05.toByte()) { "Upstream PT returned invalid SOCKS version" }
        if (args.isNotEmpty()) {
            require(greeting[1] == 0x02.toByte()) { "Upstream PT rejected RFC1929 auth" }
            val (username, password) = splitPtAuthArgs(args)
            output.write(byteArrayOf(0x01, username.size.toByte()))
            output.write(username)
            output.write(byteArrayOf(password.size.toByte()))
            output.write(password)
            output.flush()
            val authReply = ByteArray(2)
            readFully(input, authReply)
            require(authReply.contentEquals(byteArrayOf(0x01, 0x00))) {
                "Upstream PT rejected SOCKS auth payload"
            }
        } else {
            require(greeting[1] == 0x00.toByte()) { "Upstream PT selected unexpected auth method" }
        }

        writeConnectRequest(output, bridgeSpec.targetHost, bridgeSpec.targetPort)
        output.flush()
        val connectReply = ByteArray(4)
        readFully(input, connectReply)
        require(connectReply[0] == 0x05.toByte()) { "Upstream PT returned invalid SOCKS reply version" }
        require(connectReply[1] == 0x00.toByte()) { "Upstream PT CONNECT failed with reply ${connectReply[1]}" }
        skipAddress(input, connectReply[3].toInt() and 0xFF)
        readU16(input)
    }

    private fun writeConnectRequest(
        output: java.io.OutputStream,
        host: String,
        port: Int,
    ) {
        val addressBytes = host.toByteArray(Charsets.UTF_8)
        val header =
            mutableListOf<Byte>().apply {
                add(0x05)
                add(0x01)
                add(0x00)
                val inetAddress = runCatching { InetAddress.getByName(host) }.getOrNull()
                when (inetAddress) {
                    is Inet4Address -> {
                        add(0x01)
                        addAll(inetAddress.address.toList())
                    }

                    is Inet6Address -> {
                        add(0x04)
                        addAll(inetAddress.address.toList())
                    }

                    else -> {
                        require(addressBytes.size <= 255) { "SOCKS target host is too long" }
                        add(0x03)
                        add(addressBytes.size.toByte())
                        addAll(addressBytes.toList())
                    }
                }
                add(((port ushr 8) and 0xFF).toByte())
                add((port and 0xFF).toByte())
            }
        output.write(header.toByteArray())
    }

    private fun writeOuterReply(
        socket: Socket,
        replyCode: Int,
    ) {
        runCatching {
            socket.getOutputStream().write(
                byteArrayOf(
                    0x05,
                    replyCode.toByte(),
                    0x00,
                    0x01,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                ),
            )
            socket.getOutputStream().flush()
        }
    }

    private fun pipeBidirectional(
        outer: Socket,
        inner: Socket,
    ) {
        val upstream =
            thread(name = "pt-socks-bridge-up-$listenPort", isDaemon = true) {
                runCatching {
                    outer.getInputStream().copyTo(inner.getOutputStream())
                    inner.shutdownOutput()
                }
            }
        runCatching {
            inner.getInputStream().copyTo(outer.getOutputStream())
            outer.shutdownOutput()
        }
        upstream.join()
    }

    private fun skipAddress(
        input: java.io.InputStream,
        atyp: Int,
    ) {
        when (atyp) {
            0x01 -> {
                input.skipNBytes(4)
            }

            0x03 -> {
                val length = readByte(input)
                input.skipNBytes(length.toLong())
            }

            0x04 -> {
                input.skipNBytes(16)
            }

            else -> {
                error("Unsupported SOCKS address type: $atyp")
            }
        }
    }

    private fun readU16(input: java.io.InputStream): Int = (readByte(input) shl 8) or readByte(input)

    private fun readByte(input: java.io.InputStream): Int =
        input.read().takeIf { it >= 0 } ?: throw EOFException("Unexpected end of SOCKS stream")

    private fun readFully(
        input: java.io.InputStream,
        target: ByteArray,
    ) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) {
                throw EOFException("Unexpected end of SOCKS stream")
            }
            offset += read
        }
    }
}
