package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.classifyFailureReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class VpnRuntimeCompositionCoordinatorTest {
    private val root: File = Files.createTempDirectory(Path.of("/tmp"), "px").toFile()

    @After
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    @Suppress("LongMethod")
    fun `native start preserves proxy ready snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val readySnapshot =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    health = "healthy",
                    listenerAddress = "127.0.0.1:18082",
                    autolearnEnabled = true,
                    capturedAt = 1787231291789069L,
                )
            val upstreamRelaySupervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    relayFactory = TestRipDpiRelayFactory(),
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore = TestRelayProfileStore(),
                    selectorRelayRuntimeProfileResolver = TestSelectorRelayRuntimeProfileResolver(),
                    relayCredentialStore = TestRelayCredentialStore(),
                )
            val warpRuntimeSupervisor =
                WarpRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    warpFactory = TestRipDpiWarpFactory(),
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                )
            val awgRuntimeSupervisor =
                AmneziaWgRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    amneziaWgFactory = NoOpRipDpiAmneziaWgFactory(),
                    runtimeConfigResolver = TestAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                )
            val proxyRuntimeSupervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory =
                        TestRipDpiProxyFactory {
                            TestProxyRuntime().apply { telemetry = readySnapshot }
                        },
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            val runtimeStack =
                SharedProxyRuntimeStack(
                    upstreamRelaySupervisor = upstreamRelaySupervisor,
                    warpRuntimeSupervisor = warpRuntimeSupervisor,
                    amneziaWgRuntimeSupervisor = awgRuntimeSupervisor,
                    proxyRuntimeSupervisor = proxyRuntimeSupervisor,
                )
            val events = mutableListOf<String>()
            val host = TestVpnServiceHost(backgroundScope)
            val receiptStore = VpnRouteLifecycleReceiptStore()
            val abandonedGeneration =
                receiptStore.beginIntended(
                    ipv6Enabled = false,
                    dns = "1.1.1.1",
                    appRoutingPlan = VpnAppRoutingPlan.Disallow(setOf("com.poyka.ripdpi")),
                    ownPackage = "com.poyka.ripdpi",
                    networkParameters = VpnTunnelNetworkParameters(),
                    apiLevel = android.os.Build.VERSION_CODES.Q,
                )
            receiptStore.abortIntended(abandonedGeneration)
            val sessionProvider =
                TestVpnTunnelSessionProvider(
                    events = events,
                    session = TestVpnTunnelSession(events = events),
                )
            val tunnelRuntime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events)),
                    vpnTunnelSessionProvider = sessionProvider,
                    routeLifecycleReceiptStore = receiptStore,
                )
            val exitHandler =
                VpnSupervisorExitHandler(
                    proxyRuntimeStack = runtimeStack,
                    upstreamRelaySupervisor = upstreamRelaySupervisor,
                    warpRuntimeSupervisor = warpRuntimeSupervisor,
                    amneziaWgRuntimeSupervisor = awgRuntimeSupervisor,
                    markForeignRelayFailed = {},
                    failAndStop = {},
                )
            val coordinator =
                VpnRuntimeCompositionCoordinator(
                    proxyRuntimeStack = runtimeStack,
                    packetBackendRuntime = LocalPacketBackendRuntime,
                    vpnTunnelRuntime = tunnelRuntime,
                    supervisorExitHandlerFor = { exitHandler },
                    applyActiveConnectionPolicy = { _, _, _, _ -> },
                )

            val session = VpnRuntimeSession("runtime-test")
            val result = coordinator.start(session, sampleResolution(mode = Mode.VPN))

            assertTrue(result is RuntimeStartEvidence.ProxySnapshot)
            val ready = result as RuntimeStartEvidence.ProxySnapshot
            assertSame(readySnapshot, ready.snapshot)
            val routeLease = session.diagnosticsInPathRouteLease
            assertNotNull(routeLease)
            assertEquals(receiptStore.capture().lifecycle?.generation, routeLease?.routeGeneration)
            assertEquals(null, routeLease?.issuedRevision)
            assertEquals(coordinator.currentLocalProxyEndpoint?.host, routeLease?.host)
            assertEquals(coordinator.currentLocalProxyEndpoint?.port, routeLease?.port)
            assertEquals(coordinator.currentLocalProxyEndpoint?.username, routeLease?.credentials?.username)
            assertEquals(coordinator.currentLocalProxyEndpoint?.password, routeLease?.credentials?.password)
            sessionProvider.session = TestVpnTunnelSession(tunFd = 8, events = events)
            coordinator.restartAfterPolicyChange(session, sampleResolution(mode = Mode.VPN), 1L, "test_recompose")
            val rebuiltLease = requireNotNull(session.diagnosticsInPathRouteLease)
            assertEquals(receiptStore.capture().lifecycle?.generation, rebuiltLease.routeGeneration)
            assertTrue(rebuiltLease.routeGeneration > requireNotNull(routeLease).routeGeneration)
            assertEquals(null, rebuiltLease.issuedRevision)
            coordinator.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun `native start without proxied encrypted dns listens on a fresh unix path`() =
        runTest {
            val fixture = compositionFixture(this, "unix")
            val session = VpnRuntimeSession("runtime-test")

            fixture.coordinator.start(session, sampleResolution(mode = Mode.VPN))

            val overrides = fixture.sessionOverrides()
            val path = overrides.getValue("listenUnixPath").jsonPrimitive.content
            assertEquals(root.absolutePath, File(path).parent)
            assertTrue(Regex("px-[0-9a-f]{32}").matches(File(path).name))
            assertTrue(overrides["listenPortOverride"].let { it == null || it == JsonNull })
            assertTrue(
                overrides
                    .getValue("authToken")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
            val config = fixture.bridge.startedConfigs.single()
            assertEquals(path, config.socks5UnixPath)
            assertNull(config.socks5Address)
            assertNull(config.socks5Port)
            val route = requireNotNull(session.tunnelSocksRoute)
            assertEquals(path, route.unixPath)
            assertEquals(path, session.diagnosticsInPathRouteLease?.unixPath)
            assertEquals(overrides.getValue("authToken").jsonPrimitive.content, route.credentials?.password)
            fixture.coordinator.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun `native start with proxied encrypted dns keeps the tcp listener`() =
        runTest {
            val fixture = compositionFixture(this, "127.0.0.1:18082")
            val session = VpnRuntimeSession("runtime-test")
            val dns = canonicalDefaultEncryptedDnsSettings().copy(routeThroughProxy = true)

            fixture.coordinator.start(session, sampleResolution(mode = Mode.VPN, activeDns = dns))

            val overrides = fixture.sessionOverrides()
            assertFalse(overrides.containsKey("listenUnixPath"))
            assertEquals("0", overrides.getValue("listenPortOverride").jsonPrimitive.content)
            val config = fixture.bridge.startedConfigs.single()
            assertEquals("127.0.0.1", config.socks5Address)
            assertEquals(18082, config.socks5Port)
            assertNull(config.socks5UnixPath)
            assertNull(session.tunnelSocksRoute?.unixPath)
            fixture.coordinator.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun `native start without a socket directory keeps the tcp listener`() =
        runTest {
            val fixture = compositionFixture(this, "127.0.0.1:18082", socketDirectory = null)

            fixture.coordinator.start(VpnRuntimeSession("runtime-test"), sampleResolution(mode = Mode.VPN))

            assertFalse(fixture.sessionOverrides().containsKey("listenUnixPath"))
            assertNull(
                fixture.bridge.startedConfigs
                    .single()
                    .socks5UnixPath,
            )
            fixture.coordinator.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun `unix session that reports a tcp listener fails to start`() =
        runTest {
            val fixture = compositionFixture(this, "127.0.0.1:18082")

            val failure =
                runCatching {
                    fixture.coordinator.start(VpnRuntimeSession("runtime-test"), sampleResolution(mode = Mode.VPN))
                }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(fixture.bridge.startedConfigs.isEmpty())
        }

    @Test
    fun `consecutive starts use different unix paths and sweep stale socket files`() =
        runTest {
            val stale = File(root, "px-" + "a".repeat(32)).apply { writeText("") }
            val unrelated =
                listOf("vp-" + "b".repeat(32), "px-short", "px-" + "C".repeat(32), "other").map {
                    File(root, it).apply { writeText("") }
                }
            val directory = File(root, "px-" + "d".repeat(32)).apply { mkdir() }
            val fixture = compositionFixture(this, "unix")

            fixture.coordinator.start(VpnRuntimeSession("runtime-one"), sampleResolution(mode = Mode.VPN))
            val first =
                fixture
                    .sessionOverrides()
                    .getValue("listenUnixPath")
                    .jsonPrimitive.content
            fixture.coordinator.stop(skipRuntimeShutdown = false)
            fixture.coordinator.start(VpnRuntimeSession("runtime-two"), sampleResolution(mode = Mode.VPN))
            val second =
                fixture
                    .sessionOverrides()
                    .getValue("listenUnixPath")
                    .jsonPrimitive.content

            assertNotEquals(first, second)
            assertFalse(stale.exists())
            assertTrue(unrelated.all { it.exists() })
            assertTrue(directory.isDirectory)
            fixture.coordinator.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun `sweep never deletes a path this process issued even without a current endpoint`() {
        val issued = File(newProxySocketPath(root)).apply { writeText("") }
        val leftover = File(root, "px-" + "f".repeat(32)).apply { writeText("") }

        sweepStaleProxySockets(root)

        assertTrue(issued.exists())
        assertFalse(leftover.exists())
    }

    @Test
    fun `sweep skips names that do not match a directory and a symlink`() {
        val target = File(root, "target").apply { writeText("") }
        val names = listOf("vp-" + "b".repeat(32), "px-short", "px-" + "C".repeat(32), "px-" + "a".repeat(33))
        val files = names.map { File(root, it).apply { writeText("") } }
        val directory = File(root, "px-" + "d".repeat(32)).apply { mkdir() }
        val link = File(root, "px-" + "e".repeat(32))
        Files.createSymbolicLink(link.toPath(), target.toPath())

        sweepStaleProxySockets(root)

        assertTrue(files.all { it.exists() })
        assertTrue(directory.isDirectory)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(target.exists())
    }

    @Test
    fun `a unix path that does not fit a socket address fails the start as a classified native failure`() =
        runTest {
            val longDirectory = File(root, "d".repeat(90)).apply { mkdirs() }
            val fixture = compositionFixture(this, "unix", socketDirectory = longDirectory)

            val failure =
                runCatching {
                    fixture.coordinator.start(VpnRuntimeSession("runtime-test"), sampleResolution(mode = Mode.VPN))
                }.exceptionOrNull()

            assertTrue(failure is SupervisorStartupFailureException)
            val reason = classifyFailureReason((failure as Exception).unwrapSupervisorStartupFailure())
            assertTrue(reason is FailureReason.NativeError)
            assertTrue(fixture.proxies.runtimes.isEmpty())
            assertTrue(fixture.bridge.startedConfigs.isEmpty())
        }

    @Test
    fun `a session that needs the tcp listener never generates a path`() =
        runTest {
            val longDirectory = File(root, "d".repeat(90)).apply { mkdirs() }
            val fixture = compositionFixture(this, "127.0.0.1:18082", socketDirectory = longDirectory)
            val dns = canonicalDefaultEncryptedDnsSettings().copy(routeThroughProxy = true)

            fixture.coordinator.start(
                VpnRuntimeSession("runtime-test"),
                sampleResolution(mode = Mode.VPN, activeDns = dns),
            )

            assertFalse(fixture.sessionOverrides().containsKey("listenUnixPath"))
            fixture.coordinator.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun `handover reuses the unix proxy while the dns plan still fits it`() =
        runTest {
            val fixture = compositionFixture(this, "unix")
            val session = VpnRuntimeSession("runtime-test")
            fixture.coordinator.start(session, sampleResolution(mode = Mode.VPN))

            fixture.coordinator.restartAfterHandover(session, sampleResolution(mode = Mode.VPN).sameRoute(), 1L)

            assertEquals(1, fixture.proxies.runtimes.size)
            fixture.coordinator.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun `handover replaces the unix proxy with a tcp listener when the dns plan needs one`() =
        runTest {
            val fixture = compositionFixture(this, "unix", "127.0.0.1:18082")
            val session = VpnRuntimeSession("runtime-test")
            fixture.coordinator.start(session, sampleResolution(mode = Mode.VPN))
            val dns = canonicalDefaultEncryptedDnsSettings().copy(routeThroughProxy = true)
            fixture.sessionProvider.session = TestVpnTunnelSession(tunFd = 8, events = fixture.events)

            val resolution = sampleResolution(mode = Mode.VPN, activeDns = dns).sameRoute()

            fixture.coordinator.restartAfterHandover(session, resolution, 1L)

            assertEquals(2, fixture.proxies.runtimes.size)
            assertFalse(fixture.sessionOverrides().containsKey("listenUnixPath"))
            assertEquals(
                18082,
                fixture.bridge.startedConfigs
                    .last()
                    .socks5Port,
            )
            fixture.coordinator.stop(skipRuntimeShutdown = false)
        }

    private fun ConnectionPolicyResolution.sameRoute() = copy(samePhysicalRoute = true)

    private class CompositionFixture(
        val coordinator: VpnRuntimeCompositionCoordinator,
        val bridge: TestTun2SocksBridge,
        val proxies: TestRipDpiProxyFactory,
        val sessionProvider: TestVpnTunnelSessionProvider,
        val events: MutableList<String>,
    ) {
        fun sessionOverrides() =
            Json
                .parseToJsonElement(requireNotNull(proxies.lastRuntime.lastPreferences).toNativeConfigJson())
                .jsonObject
                .getValue("sessionOverrides")
                .jsonObject
    }

    @Suppress("LongMethod")
    private fun compositionFixture(
        scope: TestScope,
        vararg listenerAddresses: String,
        socketDirectory: File? = root,
    ): CompositionFixture {
        val backgroundScope: CoroutineScope = scope.backgroundScope
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val created = AtomicInteger()
        val proxies =
            TestRipDpiProxyFactory {
                val address = listenerAddresses[minOf(created.getAndIncrement(), listenerAddresses.lastIndex)]
                TestProxyRuntime().apply {
                    telemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            state = "running",
                            health = "healthy",
                            listenerAddress = address,
                        )
                }
            }
        val upstreamRelaySupervisor =
            UpstreamRelaySupervisor(
                scope = backgroundScope,
                dispatcher = dispatcher,
                relayFactory = TestRipDpiRelayFactory(),
                naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                relayProfileStore = TestRelayProfileStore(),
                selectorRelayRuntimeProfileResolver = TestSelectorRelayRuntimeProfileResolver(),
                relayCredentialStore = TestRelayCredentialStore(),
            )
        val warpRuntimeSupervisor =
            WarpRuntimeSupervisor(
                scope = backgroundScope,
                dispatcher = dispatcher,
                warpFactory = TestRipDpiWarpFactory(),
                runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
            )
        val awgRuntimeSupervisor =
            AmneziaWgRuntimeSupervisor(
                scope = backgroundScope,
                dispatcher = dispatcher,
                amneziaWgFactory = NoOpRipDpiAmneziaWgFactory(),
                runtimeConfigResolver = TestAmneziaWgRuntimeConfigResolver(),
                activeRegistry = ActiveAwgRuntimeRegistry(),
            )
        val runtimeStack =
            SharedProxyRuntimeStack(
                upstreamRelaySupervisor = upstreamRelaySupervisor,
                warpRuntimeSupervisor = warpRuntimeSupervisor,
                amneziaWgRuntimeSupervisor = awgRuntimeSupervisor,
                proxyRuntimeSupervisor =
                    ProxyRuntimeSupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        ripDpiProxyFactory = proxies,
                        networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    ),
            )
        val events = mutableListOf<String>()
        val bridge = TestTun2SocksBridge(events)
        val sessionProvider =
            TestVpnTunnelSessionProvider(events = events, session = TestVpnTunnelSession(events = events))
        val tunnelRuntime =
            VpnTunnelRuntime(
                vpnHost = TestVpnServiceHost(backgroundScope),
                environment =
                    VpnTunnelRuntimeEnvironment(proxySocketDirectory = socketDirectory),
                appSettingsRepository = TestAppSettingsRepository(),
                proxyGroupRepository = TestProxyGroupRepository(),
                tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                vpnTunnelSessionProvider = sessionProvider,
                routeLifecycleReceiptStore = VpnRouteLifecycleReceiptStore(),
            )
        val exitHandler =
            VpnSupervisorExitHandler(
                proxyRuntimeStack = runtimeStack,
                upstreamRelaySupervisor = upstreamRelaySupervisor,
                warpRuntimeSupervisor = warpRuntimeSupervisor,
                amneziaWgRuntimeSupervisor = awgRuntimeSupervisor,
                markForeignRelayFailed = {},
                failAndStop = {},
            )
        return CompositionFixture(
            VpnRuntimeCompositionCoordinator(
                proxyRuntimeStack = runtimeStack,
                packetBackendRuntime = LocalPacketBackendRuntime,
                vpnTunnelRuntime = tunnelRuntime,
                supervisorExitHandlerFor = { exitHandler },
                applyActiveConnectionPolicy = { _, _, _, _ -> },
                ioDispatcher = dispatcher,
            ),
            bridge,
            proxies,
            sessionProvider,
            events,
        )
    }
}
