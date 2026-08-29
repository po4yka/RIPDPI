package com.poyka.ripdpi.target37

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.data.AndroidLocalNetworkAccess
import com.poyka.ripdpi.data.LocalNetworkAccessRequiredException
import com.poyka.ripdpi.data.LocalNetworkPermission
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** Runs only in the mandatory target37 lane, in separate grant/deny/regrant processes. */
class LocalNetworkRuntimeTest {
    @Test
    fun tcpUdpAndLoopbackRespectPermission() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val arguments = InstrumentationRegistry.getArguments()
            val host = requireNotNull(arguments.getString("lanHost"))
            val tcpPort = requireNotNull(arguments.getString("lanTcpPort")).toInt()
            val udpPort = requireNotNull(arguments.getString("lanUdpPort")).toInt()
            val granted = requireNotNull(arguments.getString("lanGranted")).toBooleanStrict()
            val address = InetAddress.getByName(host)
            assertEquals(37, Build.VERSION.SDK_INT)
            assertEquals(37, context.applicationInfo.targetSdkVersion)
            assertEquals(PageSize16Kb, Os.sysconf(OsConstants._SC_PAGESIZE))
            assertTrue("A real LAN address is required, not adb reverse or an emulator host alias", isLan(address))
            assertEquals(
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED,
                ContextCompat.checkSelfPermission(context, LocalNetworkPermission),
            )

            val access = AndroidLocalNetworkAccess(context)
            verifyLoopback(access)
            if (granted) {
                access.requireDirectEndpoint(host, tcpPort)
                assertArrayEquals(Payload, tcpEcho(address, tcpPort))
                assertArrayEquals(Payload, udpEcho(address, udpPort))
            } else {
                val preflight = runCatching { access.requireDirectEndpoint(host, tcpPort) }.exceptionOrNull()
                assertTrue(preflight is LocalNetworkAccessRequiredException)
                // A failed socket alone is not an LNP diagnosis. The driver sandwiches this phase
                // between successful grant/regrant probes against the same live endpoint.
                assertNotNull(socketFailure { tcpEcho(address, tcpPort) })
                assertNotNull(socketFailure { udpEcho(address, udpPort) })
            }
            loadPackagedNativeLibraries(context)
        }

    private fun loadPackagedNativeLibraries(context: Context) {
        val abi = Build.SUPPORTED_ABIS.first()
        val prefix = "lib/$abi/"
        val applicationInfo = context.applicationInfo
        val apkPaths = listOf(applicationInfo.sourceDir) + applicationInfo.splitSourceDirs.orEmpty()
        val packagedLibraries =
            apkPaths
                .flatMap { apkPath ->
                    ZipFile(apkPath).use { apk ->
                        apk
                            .entries()
                            .asSequence()
                            .filter { entry -> entry.name.startsWith(prefix) }
                            .map { entry -> entry.name.removePrefix(prefix) }
                            .filter { name -> entryIsNativeLibrary(name) && '/' !in name }
                            .map { name -> name.removePrefix("lib").removeSuffix(".so") }
                            .toList()
                    }
                }.toSortedSet()
        assertEquals(emptySet<String>(), RequiredNativeLibraries - packagedLibraries)
        packagedLibraries.forEach(System::loadLibrary)
    }

    private fun entryIsNativeLibrary(name: String): Boolean = name.startsWith("lib") && name.endsWith(".so")

    private suspend fun verifyLoopback(access: AndroidLocalNetworkAccess) {
        val address = InetAddress.getByName("127.0.0.1")
        access.requireListener(address.hostAddress!!)
        ServerSocket(0, 1, address).use { server ->
            server.soTimeout = TimeoutMs
            val responder =
                thread(name = "target37-loopback") {
                    server.accept().use { socket ->
                        socket.soTimeout = TimeoutMs
                        val payload = ByteArray(Payload.size)
                        socket.getInputStream().readFully(payload)
                        socket.getOutputStream().write(payload)
                    }
                }
            try {
                access.requireDirectEndpoint(address.hostAddress!!, server.localPort)
                assertArrayEquals(Payload, tcpEcho(address, server.localPort))
            } finally {
                responder.join(TimeoutMs.toLong())
            }
        }
        DatagramSocket(0, address).use { server ->
            server.soTimeout = TimeoutMs
            val responder =
                thread(name = "target37-udp-loopback") {
                    val packet = DatagramPacket(ByteArray(BufferSize), BufferSize)
                    server.receive(packet)
                    server.send(packet)
                }
            try {
                assertArrayEquals(Payload, udpEcho(address, server.localPort))
            } finally {
                responder.join(TimeoutMs.toLong())
            }
        }
    }

    private fun tcpEcho(
        address: InetAddress,
        port: Int,
    ): ByteArray =
        Socket().use { socket ->
            socket.soTimeout = TimeoutMs
            socket.connect(InetSocketAddress(address, port), TimeoutMs)
            socket.getOutputStream().write(Payload)
            ByteArray(Payload.size).also { socket.getInputStream().readFully(it) }
        }

    private fun udpEcho(
        address: InetAddress,
        port: Int,
    ): ByteArray =
        DatagramSocket().use { socket ->
            socket.soTimeout = TimeoutMs
            socket.connect(address, port)
            socket.send(DatagramPacket(Payload, Payload.size))
            val response = DatagramPacket(ByteArray(BufferSize), BufferSize)
            socket.receive(response)
            response.data.copyOfRange(response.offset, response.offset + response.length)
        }

    private fun java.io.InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IOException("LAN echo closed before the response was complete")
            offset += count
        }
    }

    private fun socketFailure(block: () -> ByteArray): IOException? =
        try {
            block()
            null
        } catch (failure: IOException) {
            failure
        }

    private fun isLan(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress &&
            address.hostAddress.orEmpty() !in setOf("10.0.2.2", "10.0.3.2") &&
            (
                address.isSiteLocalAddress || address.isLinkLocalAddress ||
                    (address.address.size == Ipv6Bytes && address.address[0].toInt() and UlaMask == UlaPrefix)
            )

    private companion object {
        const val TimeoutMs = 5_000
        const val BufferSize = 256
        const val PageSize16Kb = 16_384L
        const val Ipv6Bytes = 16
        const val UlaMask = 0xfe
        const val UlaPrefix = 0xfc
        val RequiredNativeLibraries =
            setOf("gojni", "ripdpi", "ripdpi-tunnel", "ripdpi-relay", "ripdpi-warp", "ripdpi-amneziawg")
        val Payload = "ripdpi-target37-lan-smoke".toByteArray()
    }
}
