@file:Suppress(
    "ComplexCondition",
    "TooGenericExceptionCaught",
    "InstanceOfCheckForException",
    "ReturnCount",
    "MagicNumber",
)

package com.poyka.ripdpi.services

import android.net.InetAddresses
import android.os.Build
import co.touchlab.kermit.Logger
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.Semaphore
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

internal fun encodeManagedClientSocksConnectRequest(
    host: String,
    port: Int,
): ByteArray {
    require(port in 1..65_535) { "SOCKS target port is out of range" }
    val request = mutableListOf<Byte>()
    request.add(0x05)
    request.add(0x01)
    request.add(0x00)
    val numericAddress = parseNumericAddressOrNull(host)
    when {
        numericAddress?.address?.size == Ipv4AddressByteCount -> {
            request.add(0x01)
            request.addAll(numericAddress.address.toList())
        }

        numericAddress?.address?.size == Ipv6AddressByteCount -> {
            request.add(0x04)
            request.addAll(numericAddress.address.toList())
        }

        else -> {
            val addressBytes = host.toByteArray(Charsets.UTF_8)
            require(addressBytes.size <= 255) { "SOCKS target host is too long" }
            request.add(0x03)
            request.add(addressBytes.size.toByte())
            request.addAll(addressBytes.toList())
        }
    }
    request.add(((port ushr 8) and 0xFF).toByte())
    request.add((port and 0xFF).toByte())
    return request.toByteArray()
}

private fun parseNumericAddressOrNull(host: String): InetAddress? {
    val trimmed = host.trim()
    if (trimmed.isEmpty()) return null
    parseIpv4Literal(trimmed)?.let { return it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && InetAddresses.isNumericAddress(trimmed)) {
        return InetAddresses.parseNumericAddress(trimmed)
    }
    return null
}

private fun parseIpv4Literal(value: String): InetAddress? {
    val octets = value.split('.')
    if (octets.size != Ipv4AddressByteCount) return null
    val bytes =
        octets.map { part ->
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part.startsWith('0')) return null
            part.toIntOrNull()?.takeIf { it in 0..255 }?.toByte() ?: return null
        }
    return InetAddress.getByAddress(bytes.toByteArray())
}

// RFC 1928 section 6 reply codes.
private const val RepFailure = 0x01
private const val RepConnectionRefused = 0x05
private const val RepCommandNotSupported = 0x07
private const val RepAddressTypeNotSupported = 0x08
private val SupportedAddressTypes = setOf(0x01, 0x03, 0x04)

private class SocksReplyException(
    val replyCode: Int,
    message: String,
) : IOException(message)

private const val Ipv4AddressByteCount = 4
private const val Ipv6AddressByteCount = 16
private const val MaxConcurrentBridgeClients = 64
private const val LocalHandshakeTimeoutMillis = 5_000
private const val PtConnectReplyTimeoutMillis = 60_000

internal class ManagedClientSocksBridge(
    private val listenHost: String,
    private val listenPort: Int,
    private val upstreamListener: InetSocketAddress,
    private val bridgeSpec: ManagedClientSocksBridgeSpec,
) : Closeable {
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val clientSlots = Semaphore(MaxConcurrentBridgeClients)

    @Volatile private var serverSocket: ServerSocket? = null

    @Volatile private var acceptThread: Thread? = null

    fun start() {
        // The bridge offers NO-AUTH, so it must never listen beyond this device.
        val loopback =
            listenHost.equals("localhost", ignoreCase = true) ||
                parseNumericAddressOrNull(listenHost)?.isLoopbackAddress == true
        require(loopback) {
            "PT SOCKS bridge must listen on a loopback address"
        }
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
            if (!clientSlots.tryAcquire()) {
                // Each client opens a PT session; refuse floods instead of spawning unbounded threads.
                runCatching { client.close() }
                continue
            }
            activeSockets += client
            thread(
                name = "pt-socks-bridge-client-$listenPort",
                isDaemon = true,
            ) {
                client.use { outer ->
                    try {
                        handleClient(outer)
                    } catch (error: Exception) {
                        // A bad or aborted client ends only its own thread, never the process.
                        Logger.d { "PT SOCKS bridge client closed: ${error.javaClass.simpleName}" }
                    } finally {
                        activeSockets -= outer
                        clientSlots.release()
                    }
                }
            }
        }
    }

    private fun handleClient(outer: Socket) {
        outer.soTimeout = LocalHandshakeTimeoutMillis
        negotiateOuterHandshake(outer)
        val upstream = connectUpstreamListener(outer)
        activeSockets += upstream
        upstream.use { inner ->
            try {
                connectUpstream(inner)
                writeOuterReply(outer, replyCode = 0x00)
                // The relayed flow has no idle limit (RFC 1928 section 6).
                outer.soTimeout = 0
                inner.soTimeout = 0
                pipeBidirectional(outer, inner)
            } catch (error: Throwable) {
                writeOuterReply(outer, replyCode = (error as? SocksReplyException)?.replyCode ?: RepFailure)
                if (error is IOException) {
                    throw error
                }
                throw IOException(error)
            } finally {
                activeSockets -= inner
            }
        }
    }

    private fun connectUpstreamListener(outer: Socket): Socket {
        val upstream = Socket()
        try {
            upstream.connect(upstreamListener, LocalHandshakeTimeoutMillis)
            // The PT replies to CONNECT only after it dials and handshakes with the bridge.
            upstream.soTimeout = PtConnectReplyTimeoutMillis
            return upstream
        } catch (error: IOException) {
            runCatching { upstream.close() }
            writeOuterReply(outer, replyCode = if (error is ConnectException) RepConnectionRefused else RepFailure)
            throw error
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
        // Reject before reading the address: the client still gets a typed reply (RFC 1928 section 6).
        val rejection =
            when {
                command != 0x01 -> RepCommandNotSupported
                atyp !in SupportedAddressTypes -> RepAddressTypeNotSupported
                else -> null
            }
        if (rejection != null) {
            writeOuterReply(socket, replyCode = rejection)
            throw SocksReplyException(rejection, "Unsupported SOCKS request: command $command, address type $atyp")
        }
        skipAddress(input, atyp)
        readU16(input)
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
        val upstreamReply = connectReply[1].toInt() and 0xFF
        if (upstreamReply != 0x00) {
            // Relay the PT's own failure code so the client can tell refusal from unreachability.
            throw SocksReplyException(upstreamReply, "Upstream PT CONNECT failed with reply $upstreamReply")
        }
        skipAddress(input, connectReply[3].toInt() and 0xFF)
        readU16(input)
    }

    private fun writeConnectRequest(
        output: java.io.OutputStream,
        host: String,
        port: Int,
    ) {
        output.write(encodeManagedClientSocksConnectRequest(host, port))
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
                }.onFailure { closeBoth(outer, inner) }
            }
        runCatching {
            inner.getInputStream().copyTo(outer.getOutputStream())
            outer.shutdownOutput()
        }.onFailure { closeBoth(outer, inner) }
        upstream.join()
    }

    // A failed direction cannot half-close, so close both and unblock the peer copy loop.
    private fun closeBoth(
        outer: Socket,
        inner: Socket,
    ) {
        runCatching { outer.close() }
        runCatching { inner.close() }
    }

    private fun skipAddress(
        input: java.io.InputStream,
        atyp: Int,
    ) {
        when (atyp) {
            0x01 -> {
                skipFully(input, 4)
            }

            0x03 -> {
                val length = readByte(input)
                skipFully(input, length.toLong())
            }

            0x04 -> {
                skipFully(input, 16)
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

    private fun skipFully(
        input: java.io.InputStream,
        count: Long,
    ) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) {
                throw EOFException("Unexpected end of SOCKS stream")
            }
            remaining -= 1
        }
    }
}
