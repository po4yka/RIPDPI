package com.poyka.ripdpi.services

import com.poyka.ripdpi.service.warp.WarpBootstrapLoopbackPortAllocator
import com.poyka.ripdpi.service.warp.WarpBootstrapProxyRuntimePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WarpBootstrapProxyRunnerTest {
    @Test
    fun `cancelled provisioning still stops the bootstrap proxy and releases its scope`() =
        runTest {
            val parent = SupervisorJob()
            val proxyFactory = TestRipDpiProxyFactory()
            val runner = runner(proxyFactory, CoroutineScope(parent + Dispatchers.Unconfined))
            val entered = CompletableDeferred<Unit>()

            val call =
                async {
                    runner.withBootstrapProxy {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()
            call.cancel()
            val failure = runCatching { call.await() }.exceptionOrNull()

            assertTrue("cancellation must reach the caller, got $failure", failure is CancellationException)
            assertEquals(1, proxyFactory.lastRuntime.stopCount)
            assertTrue("bootstrap scope leaked", parent.children.none())
        }

    @Test
    fun `failed bootstrap proxy start releases its scope`() =
        runTest {
            val parent = SupervisorJob()
            val proxyFactory =
                TestRipDpiProxyFactory { TestProxyRuntime().apply { startFailure = IOException("bind failed") } }
            val runner = runner(proxyFactory, CoroutineScope(parent + Dispatchers.Unconfined))

            val failure = runCatching { runner.withBootstrapProxy { "unreachable" } }.exceptionOrNull()

            assertTrue("start failure must reach the caller, got $failure", failure != null)
            assertTrue("bootstrap scope leaked", parent.children.none())
        }

    private fun runner(
        proxyFactory: TestRipDpiProxyFactory,
        scope: CoroutineScope,
    ): ManagedWarpBootstrapProxyRunner =
        ManagedWarpBootstrapProxyRunner(
            portAllocator = WarpBootstrapLoopbackPortAllocator(),
            runtimePolicy = WarpBootstrapProxyRuntimePolicy(TestAppSettingsRepository()),
            bootstrapProxyRuntimeSupervisorSessionFactory =
                object : BootstrapProxyRuntimeSupervisorSessionFactory {
                    override fun create(scope: CoroutineScope): ProxyRuntimeSupervisor =
                        DefaultProxyRuntimeSupervisorFactory(proxyFactory, NoStoreHostAutolearnController).create(
                            scope = scope,
                            dispatcher = Dispatchers.Unconfined,
                            networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                        )
                },
            scope = scope,
        )
}
