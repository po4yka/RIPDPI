package com.poyka.ripdpi.services

import android.app.Application
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.inject.Provider

/**
 * Verifies that [DefaultCloudflarePublishRuntimeFactory] hands out a fresh
 * [CloudflarePublishRuntime] per session instead of re-using a singleton instance, so the
 * runtime's per-session mutable state never leaks across sessions.
 */
@RunWith(RobolectricTestRunner::class)
class CloudflarePublishRuntimeFactoryTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    private val stubRelayFactory =
        object : RipDpiRelayFactory {
            override fun create(): RipDpiRelayRuntime =
                object : RipDpiRelayRuntime {
                    override suspend fun start(config: ResolvedRipDpiRelayConfig): Int = 0

                    override suspend fun awaitReady(timeoutMillis: Long) = Unit

                    override suspend fun stop() = Unit

                    override suspend fun pollTelemetry(): NativeRuntimeSnapshot =
                        NativeRuntimeSnapshot(source = "relay")
                }
        }

    private fun publishManager(): CloudflarePublishManager =
        CloudflarePublishManager(
            context = context,
            configParser = CloudflarePublishConfigParser(),
            processSupervisor =
                CloudflarePublishProcessSupervisor(
                    binaryExtractor = CloudflarePublishBinaryExtractor(context = context),
                    versionProbe = CloudflarePublishVersionProbe(),
                    launchPlanBuilder = CloudflaredLaunchPlanBuilder(CloudflarePublishConfigParser()),
                    outputReader = CloudflarePublishProcessOutputReader(),
                ),
            readinessPoller = CloudflarePublishReadinessPoller(),
            telemetryProjector = CloudflarePublishTelemetryProjector(),
        )

    @Test
    fun `create returns a distinct runtime instance on every call`() {
        val sharedManager = publishManager()
        val factory =
            DefaultCloudflarePublishRuntimeFactory(
                runtimeProvider =
                    Provider {
                        CloudflarePublishRuntime(
                            relayFactory = stubRelayFactory,
                            publishManager = sharedManager,
                        )
                    },
            )

        val first = factory.create()
        val second = factory.create()

        assertNotSame("Expected a fresh runtime per session, but the factory re-used one", first, second)
    }
}
