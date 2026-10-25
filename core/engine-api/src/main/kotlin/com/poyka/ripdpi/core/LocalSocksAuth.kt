package com.poyka.ripdpi.core

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * RFC 1929 username/password for an app-owned loopback SOCKS5 listener (native relay, Xray inbound).
 *
 * Android loopback is reachable by every installed app, so each runtime session gets a fresh pair from a
 * CSPRNG. The pair lives only in memory: it is never logged, persisted, exported, or put into telemetry.
 */
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

/**
 * Process-local map from an app-owned loopback SOCKS5 listener to the credentials its owner issued.
 *
 * The owner registers a listener once it reports its address and unregisters it when it stops. In-app clients
 * that know only the listener address (probes driven by relay telemetry) read the credentials here, so the
 * secret never travels through telemetry or settings.
 */
object LocalSocksCredentialRegistry {
    private val entries = ConcurrentHashMap<String, LocalSocksCredentials>()

    fun register(
        host: String,
        port: Int,
        credentials: LocalSocksCredentials,
    ) {
        entries[key(host, port)] = credentials
    }

    /** Removes the entry only while it still holds [credentials], so a newer listener on the port keeps its own. */
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

/** Minimal SOCKS5 client for app-owned loopback listeners (RFC 1928 with RFC 1929 authentication). */
object LocalSocks5Client {
    /**
     * Negotiates the method and, with [credentials], authenticates. Only the method that matches
     * [credentials] is offered, so a listener that requires authentication never gets a NO-AUTH session.
     *
     * @throws ProtocolException when the listener refuses the method or the credentials.
     */
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

    /**
     * Authenticates on the already connected [socket] and opens a CONNECT tunnel to [target]. An unresolved
     * [target] is sent as a domain name, so the listener resolves it, as `java.net` SOCKS does.
     *
     * @throws ProtocolException when the listener refuses the handshake or the CONNECT.
     */
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
        if (input.readByte() != SocksVersion || input.readByte() != ReplySucceeded) {
            throw ProtocolException("SOCKS CONNECT was rejected")
        }
        input.readByte()
        val boundAddressBytes =
            when (input.readByte()) {
                AddressIpv4 -> Ipv4Bytes
                AddressIpv6 -> Ipv6Bytes
                AddressDomain -> input.readUnsignedByte()
                else -> throw ProtocolException("SOCKS CONNECT reply address type is unsupported")
            }
        input.readFully(ByteArray(boundAddressBytes + PortBytes))
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
private const val ReplySucceeded: Byte = 0
private const val AddressIpv4: Byte = 1
private const val AddressDomain: Byte = 3
private const val AddressIpv6: Byte = 4
private const val Ipv4Bytes = 4
private const val Ipv6Bytes = 16
private const val PortBytes = 2
