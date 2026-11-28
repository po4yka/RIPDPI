package com.poyka.ripdpi.integration

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.core.LocalSocksCredentials
import com.poyka.ripdpi.services.LocalSocksRoute
import com.poyka.ripdpi.services.connectProxySocket
import com.poyka.ripdpi.services.localSocksProxy
import com.poyka.ripdpi.services.openTunnel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class LocalSocksUnixRouteInstrumentedTest {
    private val login = LocalSocksCredentials("unix-user", "unix-pass")
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("origin.test").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val closeables = mutableListOf<AutoCloseable>()
    private lateinit var socksServer: UnixSocksServer
    private lateinit var route: LocalSocksRoute
    private lateinit var tlsOrigin: Origin
    private lateinit var plainOrigin: Origin

    @Before
    fun startServers() {
        val cacheDir = ApplicationProvider.getApplicationContext<Context>().cacheDir
        val path = File(cacheDir, "socks-${System.nanoTime()}.sock").absolutePath
        tlsOrigin = Origin(serverTls.sslContext().serverSocketFactory.createServerSocket(0, 8, loopback()))
        plainOrigin = Origin(ServerSocket(0, 8, loopback()))
        socksServer = UnixSocksServer(path, login)
        closeables += listOf(tlsOrigin, plainOrigin, socksServer)
        route = LocalSocksRoute("", 0, login, unixPath = path)
    }

    @After
    fun stopServers() = closeables.forEach { it.close() }

    @Test
    fun httpsGetThroughUnixRouteReturnsOriginBody() {
        val response =
            client()
                .newCall(Request.Builder().url("https://origin.test:${tlsOrigin.port}/hello").build())
                .execute()

        response.use {
            assertEquals(200, it.code)
            assertEquals(OriginBody, it.body.string())
        }
        assertEquals(listOf("unix-user:unix-pass"), socksServer.logins)
        assertEquals(listOf("domain:origin.test:${tlsOrigin.port}"), socksServer.targets)
    }

    @Test
    fun plainHttpGetThroughUnixRouteReturnsOriginBody() {
        val response =
            client()
                .newCall(Request.Builder().url("http://origin.test:${plainOrigin.port}/hello").build())
                .execute()

        response.use { assertEquals(OriginBody, it.body.string()) }
        assertEquals(listOf("domain:origin.test:${plainOrigin.port}"), socksServer.targets)
    }

    @Test
    fun wrongCredentialsFailWithProtocolException() {
        val wrong = route.copy(credentials = LocalSocksCredentials("unix-user", "other"))
        val call = client(wrong).newCall(Request.Builder().url("http://origin.test:${plainOrigin.port}/").build())

        assertThrows(ProtocolException::class.java) { call.execute() }
        assertTrue(socksServer.targets.isEmpty())
    }

    @Test
    fun openTunnelReachesOriginForIpv4AndIpv6Targets() {
        val targets =
            listOf(
                InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), plainOrigin.port),
                InetSocketAddress(InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 }), plainOrigin.port),
            )

        targets.forEach { target ->
            route.connectProxySocket(TimeoutMillis).use { socket ->
                socket.soTimeout = TimeoutMillis
                route.openTunnel(socket, target)
                assertEquals(OriginBody, httpGet(socket))
            }
        }
        assertEquals(
            listOf(
                "ipv4",
                "ipv6",
            ).zip(targets) { family, target -> "$family:${target.address.hostAddress}:${target.port}" },
            socksServer.targets,
        )
    }

    @Test
    fun readTimeoutFiresWhenOriginStalls() {
        val client =
            client()
                .newBuilder()
                .readTimeout(1, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        val started = System.nanoTime()

        assertThrows(SocketTimeoutException::class.java) {
            client.newCall(Request.Builder().url("http://origin.test:${plainOrigin.port}/stall").build()).execute()
        }

        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("elapsed $elapsedMillis ms", elapsedMillis in 900..8_000)
    }

    @Test
    fun rawReadWithShortTimeoutThrowsSocketTimeout() {
        val target = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), plainOrigin.port)
        route.connectProxySocket(TimeoutMillis).use { socket ->
            route.openTunnel(socket, target)
            socket.soTimeout = 300

            assertThrows(SocketTimeoutException::class.java) { socket.getInputStream().read() }
        }
    }

    @Test
    fun serverSeesEndOfStreamAfterClientCloses() {
        val target = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), plainOrigin.port)
        val socket = route.connectProxySocket(TimeoutMillis)
        route.openTunnel(socket, target)

        socket.close()

        assertTrue(socksServer.clientEof.await(5, TimeUnit.SECONDS))
    }

    private fun client(useRoute: LocalSocksRoute = route): OkHttpClient =
        OkHttpClient
            .Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .callTimeout(20, TimeUnit.SECONDS)
            .localSocksProxy(useRoute)
            .build()

    private fun httpGet(socket: Socket): String {
        val request = "GET /hello HTTP/1.1\r\nHost: origin.test\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        return socket
            .getInputStream()
            .readBytes()
            .decodeToString()
            .substringAfter("\r\n\r\n")
    }

    private fun loopback(): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private class Origin(
        private val server: ServerSocket,
    ) : AutoCloseable {
        val port: Int get() = server.localPort
        private val sockets = CopyOnWriteArrayList<Socket>()

        init {
            thread(isDaemon = true, name = "unix-route-origin") {
                try {
                    while (true) {
                        val socket = server.accept()
                        sockets += socket
                        thread(isDaemon = true) { serve(socket) }
                    }
                } catch (_: IOException) {
                    return@thread
                }
            }
        }

        private fun serve(socket: Socket) {
            try {
                val input = socket.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    if (next < 0) return
                    head.append(next.toChar())
                }
                if (head.startsWith("GET /stall")) {
                    input.read()
                    return
                }
                val body = OriginBody.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body)
                    flush()
                }
                socket.shutdownOutput()
                input.read()
            } catch (_: IOException) {
                return
            } finally {
                socket.close()
            }
        }

        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private class UnixSocksServer(
        private val path: String,
        private val expected: LocalSocksCredentials,
    ) : AutoCloseable {
        val logins = CopyOnWriteArrayList<String>()
        val targets = CopyOnWriteArrayList<String>()
        val clientEof = CountDownLatch(1)
        private val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
        private val server: LocalServerSocket
        private val clients = CopyOnWriteArrayList<LocalSocket>()

        init {
            File(path).delete()
            bound.bind(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            Os.listen(bound.fileDescriptor, 8)
            server = LocalServerSocket(bound.fileDescriptor)
            thread(isDaemon = true, name = "unix-route-socks") {
                try {
                    while (true) {
                        val client = server.accept()
                        clients += client
                        thread(isDaemon = true) { serve(client) }
                    }
                } catch (_: IOException) {
                    return@thread
                }
            }
        }

        private fun serve(client: LocalSocket) {
            try {
                val input = DataInputStream(client.inputStream)
                val output = client.outputStream
                input.readUnsignedByte()
                input.readFully(ByteArray(input.readUnsignedByte()))
                output.write(byteArrayOf(5, 2))
                if (!authenticate(input, output)) return
                val target = readTarget(input) ?: return
                val origin = Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), target.second)
                targets += target.first
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                origin.use { pipe(client, it) }
            } catch (_: IOException) {
                return
            } finally {
                client.close()
            }
        }

        private fun authenticate(
            input: DataInputStream,
            output: OutputStream,
        ): Boolean {
            input.readUnsignedByte()
            val user = ByteArray(input.readUnsignedByte()).also(input::readFully).decodeToString()
            val password = ByteArray(input.readUnsignedByte()).also(input::readFully).decodeToString()
            val ok = user == expected.username && password == expected.password
            output.write(byteArrayOf(1, if (ok) 0 else 1))
            output.flush()
            if (ok) logins += "$user:$password"
            return ok
        }

        private fun readTarget(input: DataInputStream): Pair<String, Int>? {
            input.readFully(ByteArray(3))
            val description =
                when (input.readUnsignedByte()) {
                    1 -> "ipv4:" + InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
                    4 -> "ipv6:" + InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
                    3 -> "domain:" + ByteArray(input.readUnsignedByte()).also(input::readFully).decodeToString()
                    else -> return null
                }
            val port = input.readUnsignedShort()
            return "$description:$port" to port
        }

        private fun pipe(
            client: LocalSocket,
            origin: Socket,
        ) {
            val upstream =
                thread(isDaemon = true) {
                    try {
                        copy(client.inputStream, origin.getOutputStream())
                    } catch (_: IOException) {
                        return@thread
                    } finally {
                        clientEof.countDown()
                        runCatching { origin.shutdownOutput() }
                    }
                }
            try {
                copy(origin.getInputStream(), client.outputStream)
            } finally {
                runCatching { client.shutdownOutput() }
                upstream.join(1_000)
            }
        }

        private fun copy(
            from: InputStream,
            to: OutputStream,
        ) {
            val buffer = ByteArray(4_096)
            while (true) {
                val read = from.read(buffer)
                if (read < 0) return
                to.write(buffer, 0, read)
                to.flush()
            }
        }

        override fun close() {
            runCatching { Os.shutdown(bound.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { server.close() }
            runCatching { bound.close() }
            clients.forEach { runCatching { it.close() } }
            File(path).delete()
        }
    }

    private companion object {
        const val OriginBody = "origin-body-through-unix-socks"
        const val TimeoutMillis = 5_000
    }
}
