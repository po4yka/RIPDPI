package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.IpComparisonAddressFamily
import com.poyka.ripdpi.core.detection.IpComparisonEndpointDescriptor
import com.poyka.ripdpi.core.detection.IpComparisonEndpointGroup
import com.poyka.ripdpi.core.detection.IpComparisonEndpointStatus
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.diagnostics.DetectionResolverConfig
import com.poyka.ripdpi.data.diagnostics.DetectionResolverMode
import com.poyka.ripdpi.data.diagnostics.DetectionResolverNetworkStack
import com.poyka.ripdpi.data.diagnostics.DnsPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class IpComparisonResolverTest {
    @Before
    fun initializeAndroidHttpResources() {
        OkHttp.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `selected direct resolver failure cannot silently use system DNS`() =
        runTest {
            val endpoint =
                IpComparisonEndpointDescriptor(
                    label = "local resolver check",
                    url = "http://localhost/ip",
                    group = IpComparisonEndpointGroup.RU,
                    addressFamily = IpComparisonAddressFamily.IPV4,
                )
            val result =
                IpComparisonChecker.check(
                    dispatchers = testDispatchers(),
                    resolverConfig =
                        DetectionResolverConfig(
                            resolverMode = DetectionResolverMode.DIRECT,
                            directDnsServers = emptyList(),
                        ),
                    endpointClient = FakeReflectionClient(),
                    endpoints = listOf(endpoint),
                )

            assertEquals(IpComparisonEndpointStatus.ERROR, result.endpoints.single().status)
            assertTrue(
                result.endpoints
                    .single()
                    .errorMessage
                    .orEmpty()
                    .contains("No valid direct DNS servers"),
            )
        }

    @Test
    fun `configured DoH supplies endpoint A and AAAA with actual reflection`() =
        runTest {
            withDnsFixture { config, networkStack, reflector ->
                val endpoint =
                    IpComparisonEndpointDescriptor(
                        label = "configured resolver reflector",
                        url = "https://example.com:${reflector.port}/ip",
                        group = IpComparisonEndpointGroup.RU,
                        addressFamily = IpComparisonAddressFamily.IPV4,
                    )
                val result =
                    IpComparisonChecker.check(
                        dispatchers = testDispatchers(),
                        resolverConfig = config,
                        endpointClient = DefaultIpComparisonEndpointClient(config, networkStack),
                        dnsResolver = ConfiguredIpComparisonDnsResolver(config, networkStack),
                        endpoints = listOf(endpoint),
                    )

                assertEquals(
                    result.endpoints.single().errorMessage,
                    IpComparisonEndpointStatus.OK,
                    result.endpoints.single().status,
                )
                assertEquals("8.8.8.8", result.endpoints.single().reflectedIp)
                assertEquals(
                    listOf("127.0.0.1"),
                    result.endpoints
                        .single()
                        .dnsRecords.aRecords,
                )
                assertEquals(
                    listOf(InetAddress.getByName("2001:4860:4860::8888").hostAddress),
                    result.endpoints
                        .single()
                        .dnsRecords.aaaaRecords,
                )
            }
        }

    @Test
    fun `cancelling configured DoH closes DNS sockets before reflection`() =
        runTest { assertSlowDnsStops(cancelExplicitly = true) }

    @Test
    fun `configured DoH deadline closes DNS sockets before reflection`() =
        runTest { assertSlowDnsStops(cancelExplicitly = false) }

    private suspend fun assertSlowDnsStops(cancelExplicitly: Boolean) =
        withContext(Dispatchers.IO) {
            val rawSocketFactory = RecordingSocketFactory()
            val sockets = rawSocketFactory.sockets
            val acquired = CountDownLatch(2)
            val listener =
                object : EventListener() {
                    override fun connectionAcquired(
                        call: Call,
                        connection: Connection,
                    ) {
                        acquired.countDown()
                    }
                }
            withDnsFixture(
                bodyDelaySeconds = 5,
                listener = listener,
                socketFactory = rawSocketFactory,
            ) { fixtureConfig, networkStack, reflector ->
                val config = fixtureConfig.copy(callTimeoutMillis = if (cancelExplicitly) 10_000 else 3_000)
                val endpoint =
                    IpComparisonEndpointDescriptor(
                        label = "cancelled resolver reflector",
                        url = "https://example.com:${reflector.port}/ip",
                        group = IpComparisonEndpointGroup.RU,
                        addressFamily = IpComparisonAddressFamily.IPV4,
                    )
                val operation =
                    async {
                        IpComparisonChecker.check(
                            dispatchers =
                                AppCoroutineDispatchers(
                                    Dispatchers.Default,
                                    Dispatchers.IO,
                                    Dispatchers.Default,
                                ),
                            resolverConfig = config,
                            endpointClient = DefaultIpComparisonEndpointClient(config, networkStack),
                            dnsResolver = ConfiguredIpComparisonDnsResolver(config, networkStack),
                            endpoints = listOf(endpoint),
                        )
                    }
                assertTrue("DoH did not acquire A/AAAA connections", acquired.await(5, TimeUnit.SECONDS))
                if (cancelExplicitly) {
                    withTimeout(1_000) {
                        operation.cancelAndJoin()
                        while (sockets.any { !it.isClosed }) delay(10)
                    }
                } else {
                    val result = withTimeout(6_000) { operation.await() }
                    assertEquals(IpComparisonEndpointStatus.ERROR, result.endpoints.single().status)
                    withTimeout(1_000) { while (sockets.any { !it.isClosed }) delay(10) }
                }
                assertEquals(2, sockets.size)
                assertTrue("Cancelled DoH sockets remain open", sockets.all { it.isClosed })
                assertNull(reflector.takeRequest(100, TimeUnit.MILLISECONDS))
            }
        }

    private suspend fun withDnsFixture(
        bodyDelaySeconds: Long = 0,
        listener: EventListener = EventListener.NONE,
        socketFactory: SocketFactory = SocketFactory.getDefault(),
        block: suspend (DetectionResolverConfig, DetectionResolverNetworkStack, MockWebServer) -> Unit,
    ) {
        val certificate =
            HeldCertificate
                .Builder()
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("example.com")
                .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates =
            HandshakeCertificates
                .Builder()
                .addTrustedCertificate(
                    certificate.certificate,
                ).build()
        MockWebServer().use { doh ->
            MockWebServer().use { reflector ->
                doh.protocols = listOf(Protocol.HTTP_1_1)
                doh.useHttps(serverCertificates.sslSocketFactory())
                reflector.useHttps(serverCertificates.sslSocketFactory())
                doh.dispatcher = DnsAnswerDispatcher(bodyDelaySeconds)
                doh.start(InetAddress.getByName("127.0.0.1"), 0)
                reflector.start(InetAddress.getByName("127.0.0.1"), 0)
                reflector.enqueue(MockResponse(body = "8.8.8.8"))
                val preset =
                    DnsPreset(
                        id = "loopback",
                        servers = listOf("127.0.0.1"),
                        dohUrl = "https://localhost:${doh.port}/dns-query",
                        dohHost = "localhost",
                    )
                val config = DetectionResolverConfig(resolverMode = DetectionResolverMode.DOH, dohPreset = preset)
                val networkStack =
                    DetectionResolverNetworkStack(
                        baseClientFactory = {
                            OkHttpClient
                                .Builder()
                                .protocols(listOf(Protocol.HTTP_1_1))
                                .eventListener(listener)
                                .socketFactory(socketFactory)
                                .sslSocketFactory(
                                    clientCertificates.sslSocketFactory(),
                                    clientCertificates.trustManager,
                                )
                        },
                    )
                block(config, networkStack, reflector)
            }
        }
    }

    // HTTPS cancellation closes the raw socket; the layered SSLSocket flag can stay false.
    private class RecordingSocketFactory : SocketFactory() {
        val sockets = ConcurrentLinkedQueue<Socket>()
        private val delegate = getDefault()

        override fun createSocket(): Socket = track(delegate.createSocket())

        override fun createSocket(
            host: String,
            port: Int,
        ): Socket = track(delegate.createSocket(host, port))

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = track(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(
            host: InetAddress,
            port: Int,
        ): Socket = track(delegate.createSocket(host, port))

        override fun createSocket(
            host: InetAddress,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = track(delegate.createSocket(host, port, localHost, localPort))

        private fun track(socket: Socket): Socket = socket.also(sockets::add)
    }

    private class DnsAnswerDispatcher(
        private val bodyDelaySeconds: Long = 0,
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val encoded = request.url.queryParameter("dns") ?: return MockResponse(code = 400)
            val query = Base64.getUrlDecoder().decode(encoded)
            var label = 12
            while (query[label].toInt() != 0) label += 1 + (query[label].toInt() and 0xff)
            val questionEnd = label + 5
            val type = ((query[questionEnd - 4].toInt() and 0xff) shl 8) or (query[questionEnd - 3].toInt() and 0xff)
            val address = InetAddress.getByName(if (type == 1) "127.0.0.1" else "2001:4860:4860::8888").address
            val body =
                Buffer()
                    .write(query, 0, 2)
                    .writeShort(0x8180)
                    .writeShort(1)
                    .writeShort(1)
                    .writeShort(0)
                    .writeShort(0)
                    .write(query, 12, questionEnd - 12)
                    .writeShort(0xc00c)
                    .writeShort(type)
                    .writeShort(1)
                    .writeInt(60)
                    .writeShort(address.size)
                    .write(address)
            return MockResponse
                .Builder()
                .setHeader("Content-Type", "application/dns-message")
                .body(body)
                .bodyDelay(bodyDelaySeconds, TimeUnit.SECONDS)
                .build()
        }
    }

    private class FakeReflectionClient : IpComparisonEndpointClient {
        override suspend fun fetchReflectedIp(endpoint: IpComparisonEndpointDescriptor): String = "8.8.8.8"
    }

    private fun testDispatchers(): AppCoroutineDispatchers =
        AppCoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default)
}
