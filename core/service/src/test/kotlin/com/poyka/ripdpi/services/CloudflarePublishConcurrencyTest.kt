package com.poyka.ripdpi.services

import android.app.Application
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Concurrency tests for [CloudflarePublishManager.start].
 *
 * Verifies that a second concurrent [CloudflarePublishManager.start] call is rejected with
 * [NativeError.AlreadyRunning], and that after [CloudflarePublishManager.stop] a new session
 * can be started.
 */
@RunWith(RobolectricTestRunner::class)
class CloudflarePublishConcurrencyTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun cfConfig(): ResolvedRipDpiRelayConfig =
        sampleResolvedRelayConfig(kind = RelayKindCloudflareTunnel)
            .copy(cloudflarePublishLocalOriginUrl = "http://localhost:43128")

    /**
     * Builds a [CloudflarePublishManager] whose [CloudflarePublishReadinessPoller.waitForCloudflaredReady]
     * suspends until [releaseSignal] is completed, allowing the test to hold a session open.
     */
    private fun hangingManager(releaseSignal: CompletableDeferred<Unit>): CloudflarePublishManager {
        val fakeProcess =
            object : Process() {
                override fun getOutputStream() = System.out

                override fun getInputStream() = System.`in`

                override fun getErrorStream() = System.`in`

                override fun waitFor() = 0

                override fun exitValue(): Int = throw IllegalThreadStateException("still running")

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
                            args: List<String>,
                        ): String? = null
                    },
                launchPlanBuilder = CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()),
                outputReader = CloudflarePublishProcessOutputReader(),
            ) {
                override fun launchOriginProcess(
                    config: ResolvedRipDpiRelayConfig,
                    originSpec: CloudflareLocalOriginSpec,
                    stateDir: File,
                    readySignal: CompletableDeferred<String>,
                    onError: (String, String) -> Unit,
                ): ManagedCloudflareProcess {
                    readySignal.complete("127.0.0.1:43128")
                    return ManagedCloudflareProcess(
                        process = fakeProcess,
                        version = null,
                        outputThread = Thread { },
                    )
                }

                override fun launchCloudflaredProcess(
                    config: ResolvedRipDpiRelayConfig,
                    originSpec: CloudflareLocalOriginSpec,
                    metricsAddress: String,
                    stateDir: File,
                    lastErrorSink: (String, String) -> Unit,
                    onRegisteredTunnelConnection: () -> Unit,
                ): ManagedCloudflareProcess =
                    ManagedCloudflareProcess(
                        process = fakeProcess,
                        version = null,
                        outputThread = Thread { },
                    )

                override fun stop(process: ManagedCloudflareProcess) = Unit
            }

        val fakePoller =
            object : CloudflarePublishReadinessPoller() {
                override suspend fun waitForOriginReady(state: RunningCloudflarePublish) {
                    state.originReady = true
                    state.originListenerAddress = "127.0.0.1:43128"
                }

                override suspend fun waitForCloudflaredReady(state: RunningCloudflarePublish) {
                    releaseSignal.await()
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

    @Test
    fun `first start succeeds`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val manager = hangingManager(release)
            val config = cfConfig()

            val job = launch { manager.start(config) }
            release.complete(Unit)
            job.join()
            // No exception thrown — test passes.
        }

    @Test
    fun `second start while first is running throws AlreadyRunning`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val manager = hangingManager(release)
            val config = cfConfig()

            // Launch first session — it suspends inside waitForCloudflaredReady.
            val firstJob = launch { manager.start(config) }

            // Yield to let the first coroutine advance past the CAS into the suspending poller.
            repeat(10) { yield() }

            var secondError: Throwable? = null
            try {
                manager.start(config)
            } catch (e: Throwable) {
                secondError = e
            }

            release.complete(Unit)
            firstJob.join()

            assertTrue(
                "expected NativeError.AlreadyRunning but got $secondError",
                secondError is NativeError.AlreadyRunning,
            )
        }

    @Test
    fun `50 concurrent starts produce exactly one success and 49 AlreadyRunning`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val manager = hangingManager(release)
            val config = cfConfig()

            val results = Array<Result<Unit>?>(50) { null }
            val jobs =
                (0 until 50).map { i ->
                    launch {
                        results[i] = runCatching { manager.start(config) }
                    }
                }

            // Allow all coroutines to race through the CAS.
            repeat(20) { yield() }

            release.complete(Unit)
            jobs.forEach { it.join() }

            val successes = results.count { it?.isSuccess == true }
            val alreadyRunning = results.count { it?.exceptionOrNull() is NativeError.AlreadyRunning }

            assertEquals("expected exactly 1 success", 1, successes)
            assertEquals("expected exactly 49 AlreadyRunning", 49, alreadyRunning)
        }

    @Test
    fun `start succeeds again after stop`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val manager = hangingManager(release)
            val config = cfConfig()

            // Run and finish a first session.
            val firstJob = launch { manager.start(config) }
            repeat(10) { yield() }
            release.complete(Unit)
            firstJob.join()

            // stop() must clear sessionActive.
            manager.stop()

            // A second start must succeed (not throw AlreadyRunning).
            var secondError: Throwable? = null
            val secondJob =
                launch {
                    secondError = runCatching { manager.start(config) }.exceptionOrNull()
                }
            // release is already completed so waitForCloudflaredReady returns immediately.
            secondJob.join()

            assertNull("second start after stop should succeed, got $secondError", secondError)
        }
}
