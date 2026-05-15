package com.poyka.ripdpi.services

import android.app.Application
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

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
class CloudflarePublishCredentialLifecycleTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun cfConfig() =
        sampleResolvedRelayConfig(kind = RelayKindCloudflareTunnel)
            .copy(
                cloudflarePublishLocalOriginUrl = "http://localhost:43129",
                cloudflareTunnelCredentialsJson =
                    """{"TunnelID":"fixture-tunnel-id","AccountTag":"fixture-account",""" +
                        """"TunnelSecret":"fixture-secret-value"}""",
            )

    private fun instantManager(): CloudflarePublishManager {
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
                        override fun extract(binaryName: String): File = File("/dev/null")
                    },
                versionProbe =
                    object : CloudflarePublishVersionProbe() {
                        override fun probe(
                            binary: File,
                            args: List<String>,
                        ): String? = null
                    },
                launchPlanBuilder = CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()),
                outputReader = CloudflarePublishProcessOutputReader(),
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
                ): ManagedCloudflareProcess = ManagedCloudflareProcess(fakeProcess, null, Thread { })

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

        return CloudflarePublishManager(
            context = context,
            configParser = CloudflarePublishConfigParser(),
            processSupervisor = fakeSupervisor,
            readinessPoller = fakePoller,
            telemetryProjector = CloudflarePublishTelemetryProjector(),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun credentialFilesUnderCacheDir(): List<File> {
        val base = context.cacheDir.resolve("cloudflare-publish")
        if (!base.exists()) return emptyList()
        return base
            .walkTopDown()
            .filter {
                it.isFile &&
                    (it.name == "cloudflared-credentials.json" || it.name == "cloudflared-config.yml")
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
    fun `credential files are deleted even when stop encounters an error`() =
        runBlocking {
            val manager = instantManager()
            manager.start(cfConfig())

            runCatching { manager.stop() }

            val remaining = credentialFilesUnderCacheDir()
            assertTrue(
                "Expected no credential files after error-stop, but found: $remaining",
                remaining.isEmpty(),
            )
        }

    @Test
    fun `stale credential dirs from a crashed previous session are wiped at construction`() {
        val staleDir =
            context.cacheDir
                .resolve("cloudflare-publish")
                .resolve("cloudflare-publish-session-stale")
                .also { it.mkdirs() }
        staleDir.resolve("cloudflared-credentials.json").writeText("""{"TunnelID":"fixture-stale"}""")

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
                stateDir.resolve("cloudflared-credentials.json").exists(),
            )
            assertTrue(
                "Expected cloudflared-config.yml to be written",
                stateDir.resolve("cloudflared-config.yml").exists(),
            )
        } finally {
            stateDir.deleteRecursively()
        }
    }
}
