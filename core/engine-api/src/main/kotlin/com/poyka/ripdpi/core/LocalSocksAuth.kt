package com.poyka.ripdpi.core

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class LocalSocksCredentials(
    val username: String,
    val password: String,
) {
    init {
        require(username.toByteArray(Charsets.UTF_8).size in CredentialFieldBytes) { "invalid SOCKS username" }
        require(password.toByteArray(Charsets.UTF_8).size in CredentialFieldBytes) { "invalid SOCKS password" }
    }

    override fun toString(): String = "LocalSocksCredentials([REDACTED])"

    companion object {
        private val random = SecureRandom()

        fun generate(): LocalSocksCredentials = LocalSocksCredentials(randomToken(), randomToken())

        private fun randomToken(): String {
            val bytes = ByteArray(TokenBytes).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

fun localSocksCredentialsOrNull(
    username: String?,
    password: String?,
): LocalSocksCredentials? =
    if (username != null &&
        password != null
    ) {
        LocalSocksCredentials(username, password)
    } else {
        null
    }

object LocalSocksCredentialRegistry {
    private val entries = ConcurrentHashMap<String, LocalSocksCredentials>()

    fun register(
        host: String,
        port: Int,
        credentials: LocalSocksCredentials,
    ) {
        entries[key(host, port)] = credentials
    }

    fun unregister(
        host: String,
        port: Int,
        credentials: LocalSocksCredentials,
    ) {
        entries.remove(key(host, port), credentials)
    }

    fun lookup(
        host: String,
        port: Int,
    ): LocalSocksCredentials? = entries[key(host, port)]

    private fun key(
        host: String,
        port: Int,
    ): String = "${host.trim().removeSurrounding("[", "]").lowercase()}:$port"
}

class LocalSocksConnectRejectedException(
    val reply: Int,
) : ProtocolException("SOCKS CONNECT was rejected")

object LocalSocks5Client {
    fun authenticate(
        input: InputStream,
        output: OutputStream,
        credentials: LocalSocksCredentials?,
    ) {
        val reader = DataInputStream(input)
        val method = if (credentials == null) MethodNoAuth else MethodUserPass
        output.write(byteArrayOf(SocksVersion, 1, method))
        output.flush()
        if (reader.readByte() != SocksVersion || reader.readByte() != method) {
            throw ProtocolException("SOCKS authentication negotiation failed")
        }
        if (credentials == null) return
        val username = credentials.username.toByteArray(Charsets.UTF_8)
        val password = credentials.password.toByteArray(Charsets.UTF_8)
        output.write(
            byteArrayOf(UserPassVersion, username.size.toByte()) + username + byteArrayOf(password.size.toByte()) +
                password,
        )
        output.flush()
        if (reader.readByte() != UserPassVersion || reader.readByte() != UserPassSucceeded) {
            throw ProtocolException("SOCKS username/password authentication failed")
        }
    }

    fun connect(
        socket: Socket,
        target: InetSocketAddress,
        credentials: LocalSocksCredentials?,
    ) {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        authenticate(input, output, credentials)
        output.write(connectRequest(target))
        output.flush()
        requireConnectSucceeded(input)
        if (input.readUnsignedByte() != 0) throw ProtocolException("SOCKS CONNECT reply reserved byte is invalid")
        val boundAddressBytes =
            when (input.readByte()) {
                AddressIpv4 -> Ipv4Bytes
                AddressIpv6 -> Ipv6Bytes
                AddressDomain -> input.readUnsignedByte()
                else -> throw ProtocolException("SOCKS CONNECT reply address type is unsupported")
            }
        input.readFully(ByteArray(boundAddressBytes + PortBytes))
    }

    private fun requireConnectSucceeded(input: DataInputStream) {
        if (input.readByte() != SocksVersion) throw ProtocolException("SOCKS CONNECT reply version is invalid")
        val reply = input.readUnsignedByte()
        if (reply != ReplySucceeded) throw LocalSocksConnectRejectedException(reply)
    }

    private fun connectRequest(target: InetSocketAddress): ByteArray {
        val resolved = target.address?.takeUnless { it.isAnyLocalAddress }
        val address =
            if (resolved != null) {
                val bytes = resolved.address
                byteArrayOf(if (bytes.size == Ipv4Bytes) AddressIpv4 else AddressIpv6) + bytes
            } else {
                val host = target.hostString.toByteArray(Charsets.UTF_8)
                if (host.size !in CredentialFieldBytes) throw ProtocolException("SOCKS target host length is invalid")
                byteArrayOf(AddressDomain, host.size.toByte()) + host
            }
        val port = target.port
        return byteArrayOf(SocksVersion, CommandConnect, 0) + address +
            byteArrayOf((port ushr Byte.SIZE_BITS).toByte(), port.toByte())
    }
}

private val CredentialFieldBytes = 1..255
private const val TokenBytes = 16
private const val SocksVersion: Byte = 5
private const val MethodNoAuth: Byte = 0
private const val MethodUserPass: Byte = 2
private const val UserPassVersion: Byte = 1
private const val UserPassSucceeded: Byte = 0
private const val CommandConnect: Byte = 1
private const val ReplySucceeded = 0
private const val AddressIpv4: Byte = 1
private const val AddressDomain: Byte = 3
private const val AddressIpv6: Byte = 4
private const val Ipv4Bytes = 4
private const val Ipv6Bytes = 16
private const val PortBytes = 2
