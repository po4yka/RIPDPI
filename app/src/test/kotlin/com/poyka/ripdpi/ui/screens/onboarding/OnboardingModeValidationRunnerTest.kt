package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeServiceStateStore
import com.poyka.ripdpi.activities.FakeStringResolver
import com.poyka.ripdpi.core.LocalSocksCredentials
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.services.DefaultServiceRuntimeRegistry
import com.poyka.ripdpi.services.LocalSocksRoute
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import com.poyka.ripdpi.services.OwnedTlsFingerprintSelection
import com.poyka.ripdpi.services.RuntimePolicyReloadIntent
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceRuntimeHandle
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private val TunnelCredentials = LocalSocksCredentials("tunnel-user", "tunnel-pass")

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnboardingModeValidationRunnerTest {
    @Test
    fun `connectivity probe runs on injected io dispatcher`() =
        runTest {
            val ioExecutor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "onboarding-io-test")
                }
            val ioDispatcher = ioExecutor.asCoroutineDispatcher()
            val serviceStateStore = FakeServiceStateStore()
            val serviceController = RunningServiceController(serviceStateStore)
            val tlsClientFactory = RecordingOwnedTlsClientFactory()
            val socks = FakeSocksListener()
            val runner =
                DefaultOnboardingModeValidationRunner(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    serviceController = serviceController,
                    serviceStateStore = serviceStateStore,
                    serviceRuntimeRegistry = registryWithTunnelRoute(socks.route(TunnelCredentials)),
                    tlsClientFactory = tlsClientFactory,
                    stringResolver = FakeStringResolver(),
                    dispatchers =
                        AppCoroutineDispatchers(
                            default = UnconfinedTestDispatcher(testScheduler),
                            io = ioDispatcher,
                            main = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            try {
                val result = runner.validate(Mode.VPN) { }

                assertTrue(result is OnboardingValidationResult.Success)
                assertEquals(listOf(Mode.VPN), serviceController.startedModes)
                assertEquals("onboarding-io-test", tlsClientFactory.callThreadName)
            } finally {
                socks.close()
                ioDispatcher.close()
                ioExecutor.shutdownNow()
            }
        }

    @Test
    fun `vpn dns probe authenticates at the tunnel listener and asks it to resolve the name`() =
        runTest {
            FakeSocksListener().use { socks ->
                val store = FakeServiceStateStore()
                val result =
                    validationRunner(
                        serviceStateStore = store,
                        serviceController = RunningServiceController(store),
                        registry = registryWithTunnelRoute(socks.route(TunnelCredentials)),
                    ).validate(Mode.VPN) { }

                assertTrue(result is OnboardingValidationResult.Success)
                assertEquals(TunnelCredentials.username, socks.username)
                assertEquals("connectivitycheck.gstatic.com", socks.targetHost)
            }
        }

    @Test
    fun `vpn validation fails at the tunnel step when no tunnel route is published`() =
        runTest {
            val store = FakeServiceStateStore()
            val result =
                validationRunner(
                    serviceStateStore = store,
                    serviceController = RunningServiceController(store),
                ).validate(Mode.VPN) { } as OnboardingValidationResult.Failed

            assertEquals(OnboardingValidationStep.Tunnel, result.failedStep)
        }

    @Test
    fun `host unreachable reply fails the dns step and offers no mode switch`() =
        runTest {
            FakeSocksListener(connectReply = 4).use { socks ->
                val store = FakeServiceStateStore()
                val result =
                    validationRunner(
                        serviceStateStore = store,
                        serviceController = RunningServiceController(store),
                        registry = registryWithTunnelRoute(socks.route(TunnelCredentials)),
                    ).validate(Mode.VPN) { } as OnboardingValidationResult.Failed

                assertEquals(OnboardingValidationStep.Dns, result.failedStep)
                assertEquals(null, result.suggestedMode)
            }
        }

    @Test
    fun `other connect rejection fails the connectivity step`() =
        runTest {
            FakeSocksListener(connectReply = 5).use { socks ->
                val store = FakeServiceStateStore()
                val result =
                    validationRunner(
                        serviceStateStore = store,
                        serviceController = RunningServiceController(store),
                        registry = registryWithTunnelRoute(socks.route(TunnelCredentials)),
                    ).validate(Mode.VPN) { } as OnboardingValidationResult.Failed

                assertEquals(OnboardingValidationStep.Connectivity, result.failedStep)
                assertEquals(Mode.Proxy, result.suggestedMode)
            }
        }

    @Test
    fun `captive portal redirect fails the connectivity step`() =
        runTest {
            FakeSocksListener().use { socks ->
                val store = FakeServiceStateStore()
                val result =
                    validationRunner(
                        serviceStateStore = store,
                        serviceController = RunningServiceController(store),
                        registry = registryWithTunnelRoute(socks.route(TunnelCredentials)),
                        tlsClientFactory = RecordingOwnedTlsClientFactory(responseCode = 302),
                    ).validate(Mode.VPN) { } as OnboardingValidationResult.Failed

                assertEquals(OnboardingValidationStep.Connectivity, result.failedStep)
            }
        }

    @Test
    fun `rejected vpn start returns permission failure without waiting for running status`() =
        runTest {
            val serviceStateStore = FakeServiceStateStore()
            val serviceController =
                RejectingServiceController(ServiceStartRejectionReason.VpnConsentMissing)
            val runner =
                validationRunner(
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                )

            val result = runner.validate(Mode.VPN) { }

            assertEquals(1, serviceController.startedModes.size)
            val failure = result as OnboardingValidationResult.Failed
            assertEquals(
                com.poyka.ripdpi.ui.screens.onboarding.OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION,
                failure.recoveryKind,
            )
            assertEquals(Mode.Proxy, failure.suggestedMode)
        }

    @Test
    fun `rejected notification start returns notification recovery without alternate mode`() =
        runTest {
            val runner =
                validationRunner(
                    serviceStateStore = FakeServiceStateStore(),
                    serviceController =
                        RejectingServiceController(
                            ServiceStartRejectionReason.NotificationsPermissionMissing,
                        ),
                )

            val result = runner.validate(Mode.VPN) { }

            val failure = result as OnboardingValidationResult.Failed
            assertEquals(
                com.poyka.ripdpi.ui.screens.onboarding.OnboardingValidationRecoveryKind.REQUEST_NOTIFICATIONS,
                failure.recoveryKind,
            )
            assertEquals(null, failure.suggestedMode)
        }

    @Test
    fun `accepted start still times out when running status never arrives`() =
        runTest {
            val runner =
                validationRunner(
                    serviceStateStore = FakeServiceStateStore(),
                    serviceController = AcceptedButIdleServiceController(),
                )

            val result = runner.validate(Mode.VPN) { }

            val failure = result as OnboardingValidationResult.Failed
            assertEquals(
                com.poyka.ripdpi.ui.screens.onboarding.OnboardingValidationRecoveryKind.RETRY,
                failure.recoveryKind,
            )
            assertEquals(Mode.Proxy, failure.suggestedMode)
        }

    private fun validationRunner(
        serviceStateStore: FakeServiceStateStore,
        serviceController: ServiceController,
        registry: ServiceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
        tlsClientFactory: OwnedTlsClientFactory = RecordingOwnedTlsClientFactory(),
    ): DefaultOnboardingModeValidationRunner =
        DefaultOnboardingModeValidationRunner(
            appSettingsRepository = FakeAppSettingsRepository(),
            serviceController = serviceController,
            serviceStateStore = serviceStateStore,
            serviceRuntimeRegistry = registry,
            tlsClientFactory = tlsClientFactory,
            stringResolver = FakeStringResolver(),
            dispatchers =
                AppCoroutineDispatchers(
                    default = UnconfinedTestDispatcher(),
                    io = UnconfinedTestDispatcher(),
                    main = UnconfinedTestDispatcher(),
                ),
        )

    private fun registryWithTunnelRoute(route: LocalSocksRoute): ServiceRuntimeRegistry =
        DefaultServiceRuntimeRegistry().apply {
            register(
                object : ServiceRuntimeHandle {
                    override val runtimeId = "test"
                    override val mode = Mode.VPN
                    override val activeConnectionPolicy = MutableStateFlow<ActiveConnectionPolicy?>(null)
                    override val tunnelSocksRoute = route

                    override suspend fun reloadConnectionPolicy(
                        intent: RuntimePolicyReloadIntent,
                        isCurrent: suspend () -> Boolean,
                    ) = false
                },
            )
        }

    /** One-connection-at-a-time SOCKS5 listener: records the RFC 1929 username and the CONNECT domain. */
    private class FakeSocksListener(
        private val connectReply: Int = 0,
    ) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

        @Volatile
        var username: String? = null

        @Volatile
        var targetHost: String? = null

        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        input.readByte()
                        input.readFully(ByteArray(input.readUnsignedByte()))
                        output.write(byteArrayOf(5, 2))
                        input.readByte()
                        username = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                        input.readFully(ByteArray(input.readUnsignedByte()))
                        output.write(byteArrayOf(1, 0))
                        input.readFully(ByteArray(4))
                        targetHost = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                        input.readFully(ByteArray(2))
                        output.write(byteArrayOf(5, connectReply.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
                        output.flush()
                        input.read()
                    }
                }
            }
        }

        fun route(credentials: LocalSocksCredentials) =
            LocalSocksRoute(server.inetAddress.hostAddress.orEmpty(), server.localPort, credentials)

        override fun close() = server.close()
    }

    private class RunningServiceController(
        private val serviceStateStore: FakeServiceStateStore,
    ) : com.poyka.ripdpi.services.TestSynchronousServiceController() {
        val startedModes = mutableListOf<Mode>()

        override fun recordStart(
            mode: Mode,
            receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt?,
        ): ServiceStartResult {
            startedModes += mode
            serviceStateStore.setStatus(AppStatus.Running, mode)
            return (receipt?.let(ServiceStartResult::Accepted) ?: ServiceStartResult.MaintenanceAccepted(mode))
        }

        override fun recordStop() {
            serviceStateStore.setStatus(AppStatus.Halted, serviceStateStore.status.value.second)
        }
    }

    private class RejectingServiceController(
        private val reason: ServiceStartRejectionReason,
    ) : com.poyka.ripdpi.services.TestSynchronousServiceController() {
        val startedModes = mutableListOf<Mode>()

        override fun recordStart(
            mode: Mode,
            receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt?,
        ): ServiceStartResult {
            startedModes += mode
            return ServiceStartResult.Rejected(mode = mode, reason = reason)
        }

        override fun recordStop() = Unit
    }

    private class AcceptedButIdleServiceController : com.poyka.ripdpi.services.TestSynchronousServiceController() {
        override fun recordStart(
            mode: Mode,
            receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt?,
        ): ServiceStartResult =
            (
                receipt?.let(ServiceStartResult::Accepted)
                    ?: ServiceStartResult.MaintenanceAccepted(mode)
            )

        override fun recordStop() = Unit
    }

    private class RecordingOwnedTlsClientFactory(
        private val responseCode: Int = 204,
    ) : OwnedTlsClientFactory {
        var callThreadName: String? = null

        override fun currentProfile(): String = "test"

        override fun selectionForAuthority(authority: String?): OwnedTlsFingerprintSelection =
            OwnedTlsFingerprintSelection(profileId = "test")

        override fun create(
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .addInterceptor(RecordingInterceptor())
                .apply(configure)
                .build()

        override fun createForAuthority(
            authority: String?,
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient = create(forcedTlsVersions, configure)

        private inner class RecordingInterceptor : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                callThreadName = Thread.currentThread().name
                return Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message("Test")
                    .body("".toResponseBody(null))
                    .build()
            }
        }
    }
}
