package com.poyka.ripdpi.services

import android.app.Application
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Verifies credential-file lifecycle for [CloudflarePublishManager]:
 *
 * 1. After a successful start + stop, no credential files remain under cacheDir.
 * 2. After start + error during stop, files are still cleaned (try/finally).
 * 3. At manager construction, stale credential dirs from a previous crashed session are wiped.
 * 4. The session stateDir is rooted under cacheDir, not filesDir.
 * 5. CloudflaredLaunchPlanBuilder writes credential files into the provided stateDir.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudflarePublishCredentialLifecycleTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun cfConfig() =
        sampleResolvedRelayConfig(kind = RelayKindCloudflareTunnel)
            .copy(
                cloudflarePublishLocalOriginUrl = "http://localhost:43129",
                cloudflareTunnelCredentialsJson =
                    """{"TunnelID":"550e8400-e29b-41d4-a716-446655440000","AccountTag":"fixture-account",""" +
                        """"TunnelSecret":"fixture-secret-value"}""",
            )

    private fun instantManager(deleteStateDirectory: ((File) -> Boolean)? = null): CloudflarePublishManager {
        val fakeProcess =
            object : Process() {
                override fun getOutputStream() = System.out

                override fun getInputStream() = System.`in`

                override fun getErrorStream() = System.`in`

                override fun waitFor() = 0

                override fun exitValue(): Int = 0

                override fun destroy() = Unit
            }

        val fakeSupervisor =
            object : CloudflarePublishProcessSupervisor(
                binaryExtractor =
                    object : CloudflarePublishBinaryExtractor(context = context) {
                        override fun extract(
                            binaryName: String,
                            tunnelMode: String,
                        ): File = File("/dev/null")
                    },
                versionProbe =
                    object : CloudflarePublishVersionProbe() {
                        override fun probe(
                            binary: File,
                            versionArguments: List<String>,
                        ): String? = null
                    },
                launchPlanBuilder = CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()),
                outputReader = CloudflarePublishProcessOutputReader(),
                protectPathProvider = ActiveProtectSocketPathProvider(),
            ) {
                override fun launchOriginProcess(
                    config: com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig,
                    originSpec: CloudflareLocalOriginSpec,
                    stateDir: File,
                    readySignal: CompletableDeferred<String>,
                    onError: (String, String) -> Unit,
                ): ManagedCloudflareProcess {
                    readySignal.complete("127.0.0.1:43129")
                    return ManagedCloudflareProcess(fakeProcess, null, Thread { })
                }

                override fun launchCloudflaredProcess(
                    config: com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig,
                    originSpec: CloudflareLocalOriginSpec,
                    metricsAddress: String,
                    stateDir: File,
                    lastErrorSink: (String, String) -> Unit,
                    onRegisteredTunnelConnection: () -> Unit,
                ): ManagedCloudflareProcess {
                    stateDir
                        .resolve(".cloudflared")
                        .also(File::mkdirs)
                        .resolve("cloudflared-credentials.json")
                        .writeText("""{"TunnelID":"550e8400-e29b-41d4-a716-446655440000"}""")
                    return ManagedCloudflareProcess(fakeProcess, null, Thread { })
                }

                override fun stop(process: ManagedCloudflareProcess) = Unit
            }

        val fakePoller =
            object : CloudflarePublishReadinessPoller() {
                override suspend fun waitForOriginReady(state: RunningCloudflarePublish) {
                    state.originReady = true
                    state.originListenerAddress = "127.0.0.1:43129"
                }

                override suspend fun waitForCloudflaredReady(state: RunningCloudflarePublish) {
                    state.cloudflaredReady = true
                }
            }

        return object : CloudflarePublishManager(
            context = context,
            configParser = CloudflarePublishConfigParser(),
            processSupervisor = fakeSupervisor,
            readinessPoller = fakePoller,
            telemetryProjector = CloudflarePublishTelemetryProjector(),
        ) {
            override fun deleteStateDirectory(stateDir: File): Boolean =
                deleteStateDirectory?.invoke(stateDir) ?: super.deleteStateDirectory(stateDir)
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun credentialFilesUnderCacheDir(): List<File> {
        val base = context.cacheDir.resolve("cloudflare-publish")
        if (!base.exists()) return emptyList()
        return base
            .walkTopDown()
            .filter {
                it.isFile &&
                    it.name in setOf("cloudflared-credentials.json", "config.yml", "tunnel-token")
            }.toList()
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `credential files are deleted after successful start and stop`() =
        runBlocking {
            val manager = instantManager()
            manager.start(cfConfig())
            manager.stop()

            val remaining = credentialFilesUnderCacheDir()
            assertTrue(
                "Expected no credential files after stop, but found: $remaining",
                remaining.isEmpty(),
            )
        }

    @Test
    fun `state deletion failure blocks restart and is retried on stop`() =
        runBlocking {
            var deleteAttempts = 0
            val manager =
                instantManager { stateDir ->
                    deleteAttempts += 1
                    deleteAttempts > 1 && stateDir.deleteRecursively()
                }
            manager.start(cfConfig())

            val stopError = runCatching { manager.stop() }.exceptionOrNull()

            assertTrue(stopError is IllegalStateException)
            assertTrue(credentialFilesUnderCacheDir().isNotEmpty())
            val restartError = runCatching { manager.start(cfConfig()) }.exceptionOrNull()
            assertTrue(restartError is NativeError.AlreadyRunning)
            manager.stop()
            assertEquals(2, deleteAttempts)
            assertTrue(credentialFilesUnderCacheDir().isEmpty())
        }

    @Test
    fun `stop attempts both helpers and retains state for retry when termination fails`() =
        runBlocking {
            val originProcess = inertProcess()
            val cloudflaredProcess = inertProcess()
            val stopped = mutableListOf<Process>()
            var failCloudflaredStop = true
            val supervisor =
                object : CloudflarePublishProcessSupervisor(
                    binaryExtractor = CloudflarePublishBinaryExtractor(context = context),
                    versionProbe = CloudflarePublishVersionProbe(),
                    launchPlanBuilder = CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()),
                    outputReader = CloudflarePublishProcessOutputReader(),
                    protectPathProvider = ActiveProtectSocketPathProvider(),
                ) {
                    override fun launchOriginProcess(
                        config: ResolvedRipDpiRelayConfig,
                        originSpec: CloudflareLocalOriginSpec,
                        stateDir: File,
                        readySignal: CompletableDeferred<String>,
                        onError: (String, String) -> Unit,
                    ): ManagedCloudflareProcess {
                        readySignal.complete("127.0.0.1:43129")
                        return ManagedCloudflareProcess(originProcess, null, Thread { })
                    }

                    override fun launchCloudflaredProcess(
                        config: ResolvedRipDpiRelayConfig,
                        originSpec: CloudflareLocalOriginSpec,
                        metricsAddress: String,
                        stateDir: File,
                        lastErrorSink: (String, String) -> Unit,
                        onRegisteredTunnelConnection: () -> Unit,
                    ): ManagedCloudflareProcess {
                        stateDir
                            .resolve(".cloudflared")
                            .also(File::mkdirs)
                            .resolve("cloudflared-credentials.json")
                            .writeText("""{"TunnelID":"550e8400-e29b-41d4-a716-446655440000"}""")
                        return ManagedCloudflareProcess(cloudflaredProcess, null, Thread { })
                    }

                    override fun stop(process: ManagedCloudflareProcess) {
                        stopped += process.process
                        if (process.process === cloudflaredProcess && failCloudflaredStop) {
                            failCloudflaredStop = false
                            error("cloudflared stop failed")
                        }
                    }
                }
            val manager =
                CloudflarePublishManager(
                    context = context,
                    configParser = CloudflarePublishConfigParser(),
                    processSupervisor = supervisor,
                    readinessPoller = instantReadinessPoller(),
                    telemetryProjector = CloudflarePublishTelemetryProjector(),
                )
            manager.start(cfConfig())

            val error = runCatching { manager.stop() }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals(listOf(cloudflaredProcess, originProcess), stopped)
            assertTrue(credentialFilesUnderCacheDir().isNotEmpty())
            val restartError = runCatching { manager.start(cfConfig()) }.exceptionOrNull()
            assertTrue(restartError is NativeError.AlreadyRunning)
            manager.stop()
            assertTrue(credentialFilesUnderCacheDir().isEmpty())
        }

    @Test
    fun `cloudflared launch failure stops partially started origin and cleans state`() =
        runBlocking {
            var stoppedOrigins = 0
            val fakeProcess =
                object : Process() {
                    override fun getOutputStream() = System.out

                    override fun getInputStream() = System.`in`

                    override fun getErrorStream() = System.`in`

                    override fun waitFor() = 0

                    override fun exitValue(): Int = 0

                    override fun destroy() = Unit
                }
            val supervisor =
                object : CloudflarePublishProcessSupervisor(
                    binaryExtractor = CloudflarePublishBinaryExtractor(context = context),
                    versionProbe = CloudflarePublishVersionProbe(),
                    launchPlanBuilder = CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()),
                    outputReader = CloudflarePublishProcessOutputReader(),
                    protectPathProvider = ActiveProtectSocketPathProvider(),
                ) {
                    override fun launchOriginProcess(
                        config: com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig,
                        originSpec: CloudflareLocalOriginSpec,
                        stateDir: File,
                        readySignal: CompletableDeferred<String>,
                        onError: (String, String) -> Unit,
                    ): ManagedCloudflareProcess = ManagedCloudflareProcess(fakeProcess, null, Thread { })

                    override fun launchCloudflaredProcess(
                        config: com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig,
                        originSpec: CloudflareLocalOriginSpec,
                        metricsAddress: String,
                        stateDir: File,
                        lastErrorSink: (String, String) -> Unit,
                        onRegisteredTunnelConnection: () -> Unit,
                    ): ManagedCloudflareProcess = error("cloudflared launch failed")

                    override fun stop(process: ManagedCloudflareProcess) {
                        stoppedOrigins += 1
                    }
                }
            val manager =
                CloudflarePublishManager(
                    context = context,
                    configParser = CloudflarePublishConfigParser(),
                    processSupervisor = supervisor,
                    readinessPoller = CloudflarePublishReadinessPoller(),
                    telemetryProjector = CloudflarePublishTelemetryProjector(),
                )

            val error = runCatching { manager.start(cfConfig()) }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals(1, stoppedOrigins)
            assertTrue(credentialFilesUnderCacheDir().isEmpty())
        }

    @Test
    fun `unreaped partial origin is retained for explicit cleanup retry`() =
        runBlocking {
            val fakeProcess = inertProcess()
            var cloudLaunchFails = true
            var originStopFails = true
            var stopCalls = 0
            val supervisor =
                object : CloudflarePublishProcessSupervisor(
                    binaryExtractor = CloudflarePublishBinaryExtractor(context = context),
                    versionProbe = CloudflarePublishVersionProbe(),
                    launchPlanBuilder = CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()),
                    outputReader = CloudflarePublishProcessOutputReader(),
                    protectPathProvider = ActiveProtectSocketPathProvider(),
                ) {
                    override fun launchOriginProcess(
                        config: ResolvedRipDpiRelayConfig,
                        originSpec: CloudflareLocalOriginSpec,
                        stateDir: File,
                        readySignal: CompletableDeferred<String>,
                        onError: (String, String) -> Unit,
                    ): ManagedCloudflareProcess {
                        readySignal.complete("127.0.0.1:43129")
                        return ManagedCloudflareProcess(fakeProcess, null, Thread { })
                    }

                    override fun launchCloudflaredProcess(
                        config: ResolvedRipDpiRelayConfig,
                        originSpec: CloudflareLocalOriginSpec,
                        metricsAddress: String,
                        stateDir: File,
                        lastErrorSink: (String, String) -> Unit,
                        onRegisteredTunnelConnection: () -> Unit,
                    ): ManagedCloudflareProcess {
                        if (cloudLaunchFails) {
                            cloudLaunchFails = false
                            error("cloudflared launch failed")
                        }
                        return ManagedCloudflareProcess(fakeProcess, null, Thread { })
                    }

                    override fun stop(process: ManagedCloudflareProcess) {
                        stopCalls += 1
                        if (originStopFails) {
                            originStopFails = false
                            error("origin could not be reaped")
                        }
                    }
                }
            val manager =
                CloudflarePublishManager(
                    context = context,
                    configParser = CloudflarePublishConfigParser(),
                    processSupervisor = supervisor,
                    readinessPoller = instantReadinessPoller(),
                    telemetryProjector = CloudflarePublishTelemetryProjector(),
                )

            val startError = runCatching { manager.start(cfConfig()) }.exceptionOrNull()

            assertTrue(startError is IllegalStateException)
            assertTrue(startError?.suppressed?.isNotEmpty() == true)
            assertTrue(runCatching { manager.start(cfConfig()) }.exceptionOrNull() is NativeError.AlreadyRunning)
            manager.stop()
            assertTrue(credentialFilesUnderCacheDir().isEmpty())
            manager.start(cfConfig())
            manager.stop()
            assertTrue(stopCalls >= 3)
        }

    @Test
    fun `one helper exit is reported without waiting for the surviving child`() =
        runBlocking {
            val originProcess = ExitProcess(exitCode = 17, exitsImmediately = true)
            val cloudflaredProcess = ExitProcess(exitCode = 0, exitsImmediately = false)
            val supervisor =
                object : CloudflarePublishProcessSupervisor(
                    binaryExtractor = CloudflarePublishBinaryExtractor(context = context),
                    versionProbe = CloudflarePublishVersionProbe(),
                    launchPlanBuilder = CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()),
                    outputReader = CloudflarePublishProcessOutputReader(),
                    protectPathProvider = ActiveProtectSocketPathProvider(),
                ) {
                    override fun launchOriginProcess(
                        config: ResolvedRipDpiRelayConfig,
                        originSpec: CloudflareLocalOriginSpec,
                        stateDir: File,
                        readySignal: CompletableDeferred<String>,
                        onError: (String, String) -> Unit,
                    ): ManagedCloudflareProcess {
                        readySignal.complete("127.0.0.1:43129")
                        return ManagedCloudflareProcess(originProcess, null, Thread { })
                    }

                    override fun launchCloudflaredProcess(
                        config: ResolvedRipDpiRelayConfig,
                        originSpec: CloudflareLocalOriginSpec,
                        metricsAddress: String,
                        stateDir: File,
                        lastErrorSink: (String, String) -> Unit,
                        onRegisteredTunnelConnection: () -> Unit,
                    ): ManagedCloudflareProcess = ManagedCloudflareProcess(cloudflaredProcess, null, Thread { })
                }
            val manager =
                CloudflarePublishManager(
                    context = context,
                    configParser = CloudflarePublishConfigParser(),
                    processSupervisor = supervisor,
                    readinessPoller = instantReadinessPoller(),
                    telemetryProjector = CloudflarePublishTelemetryProjector(),
                )
            manager.start(cfConfig())
            var exitCode: Int? = null

            val elapsedMillis = measureTimeMillis { exitCode = manager.waitForUnexpectedExit() }

            assertEquals(17, exitCode)
            assertTrue("surviving helper delayed exit propagation for ${elapsedMillis}ms", elapsedMillis < 500)
            manager.stop()
            assertTrue(cloudflaredProcess.destroyed)
        }

    @Test
    fun `relay factory failure rolls back ready publish helpers and credentials`() =
        runBlocking {
            val manager = instantManager()
            val runtime =
                CloudflarePublishRuntime(
                    relayFactory =
                        object : RipDpiRelayFactory {
                            override fun create(): RipDpiRelayRuntime = error("relay factory failed")
                        },
                    publishManager = manager,
                )

            val error = runCatching { runtime.start(cfConfig()) }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(credentialFilesUnderCacheDir().isEmpty())
            val restartError = runCatching { manager.start(cfConfig()) }.exceptionOrNull()
            assertNull("publish manager remained active after relay factory failure", restartError)
            manager.stop()
        }

    @Test
    fun `relay start failure rolls back ready publish helpers and credentials`() =
        runBlocking {
            val manager = instantManager()
            val runtime =
                CloudflarePublishRuntime(
                    relayFactory =
                        object : RipDpiRelayFactory {
                            override fun create(): RipDpiRelayRuntime =
                                object : RipDpiRelayRuntime {
                                    override suspend fun start(config: ResolvedRipDpiRelayConfig): Int =
                                        error("relay start failed")

                                    override suspend fun awaitReady(timeoutMillis: Long) = Unit

                                    override suspend fun stop() = Unit

                                    override suspend fun pollTelemetry(): NativeRuntimeSnapshot =
                                        NativeRuntimeSnapshot(source = "relay")
                                }
                        },
                    publishManager = manager,
                )

            val error = runCatching { runtime.start(cfConfig()) }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(credentialFilesUnderCacheDir().isEmpty())
            val restartError = runCatching { manager.start(cfConfig()) }.exceptionOrNull()
            assertNull("publish manager remained active after relay start failure", restartError)
            manager.stop()
        }

    @Test
    fun `stale credential dirs from a crashed previous session are wiped at construction`() {
        val staleDir =
            context.cacheDir
                .resolve("cloudflare-publish")
                .resolve("cloudflare-publish-session-stale")
                .also { it.mkdirs() }
        staleDir
            .resolve(".cloudflared")
            .also(File::mkdirs)
            .resolve("cloudflared-credentials.json")
            .writeText("""{"TunnelID":"fixture-stale"}""")

        assertTrue("Precondition: stale dir should exist", staleDir.exists())

        instantManager()

        assertFalse(
            "Expected stale credential dir to be wiped at manager construction",
            staleDir.exists(),
        )
    }

    @Test
    fun `stateDir is rooted under cacheDir not filesDir`() =
        runBlocking {
            val manager = instantManager()
            manager.start(cfConfig())

            val sessionDirsUnderCache =
                context.cacheDir
                    .resolve("cloudflare-publish")
                    .takeIf { it.exists() }
                    ?.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("cloudflare-publish-session-") }
                    .orEmpty()

            val sessionDirsUnderFiles =
                context.filesDir
                    .resolve("cloudflare-publish")
                    .takeIf { it.exists() }
                    ?.listFiles()
                    ?.filter { it.isDirectory }
                    .orEmpty()

            assertTrue(
                "Expected session dir under cacheDir but found none",
                sessionDirsUnderCache.isNotEmpty(),
            )
            assertTrue(
                "Expected no session dirs under filesDir but found: $sessionDirsUnderFiles",
                sessionDirsUnderFiles.isEmpty(),
            )

            manager.stop()
        }

    @Test
    fun `launch plan builder writes credential files into provided stateDir`() {
        val stateDir = context.cacheDir.resolve("cloudflare-publish-builder-test").also { it.mkdirs() }
        try {
            val config = cfConfig()
            val originSpec =
                CloudflarePublishConfigParser()
                    .parseLocalOriginSpec(config.cloudflarePublishLocalOriginUrl)

            CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()).build(
                config = config,
                originSpec = originSpec,
                metricsAddress = "127.0.0.1:9999",
                stateDir = stateDir,
            )

            assertTrue(
                "Expected cloudflared-credentials.json to be written",
                stateDir.resolve(".cloudflared/cloudflared-credentials.json").exists(),
            )
            assertTrue(
                "Expected cloudflared-config.yml to be written",
                stateDir.resolve(".cloudflared/config.yml").exists(),
            )
        } finally {
            stateDir.deleteRecursively()
        }
    }

    private fun inertProcess(): Process =
        object : Process() {
            override fun getOutputStream() = System.out

            override fun getInputStream() = System.`in`

            override fun getErrorStream() = System.`in`

            override fun waitFor() = 0

            override fun exitValue(): Int = 0

            override fun destroy() = Unit
        }

    private fun instantReadinessPoller(): CloudflarePublishReadinessPoller =
        object : CloudflarePublishReadinessPoller() {
            override suspend fun waitForOriginReady(state: RunningCloudflarePublish) {
                state.originReady = true
                state.originListenerAddress = "127.0.0.1:43129"
            }

            override suspend fun waitForCloudflaredReady(state: RunningCloudflarePublish) {
                state.cloudflaredReady = true
            }
        }

    private class ExitProcess(
        private val exitCode: Int,
        private val exitsImmediately: Boolean,
    ) : Process() {
        var destroyed = false

        override fun getOutputStream() = System.out

        override fun getInputStream() = System.`in`

        override fun getErrorStream() = System.`in`

        override fun waitFor(): Int {
            if (!exitsImmediately && !destroyed) Thread.sleep(750)
            return exitCode
        }

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            if (!exitsImmediately && !destroyed) {
                Thread.sleep(minOf(unit.toMillis(timeout), 25L))
            }
            return exitsImmediately || destroyed
        }

        override fun exitValue(): Int =
            if (exitsImmediately || destroyed) exitCode else throw IllegalThreadStateException("still running")

        override fun destroy() {
            destroyed = true
        }
    }
}
