package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnEncryptedDnsRecoveryOwnershipTest {
    @Test
    fun `DNS recovery waits for refresh ownership and discards a replaced session`() =
        runTest {
            val oldSession = VpnRuntimeSession("old")
            var activeSession = oldSession
            val refreshMutex = Mutex(locked = true)
            val dependencies =
                object : VpnTunnelRefreshDependencies {
                    override val mutex = refreshMutex
                    override val vpnTunnelRuntime: VpnTunnelRuntime
                        get() = error("Stale telemetry must not read consumed tunnel state")
                    override val dnsPolicyCoordinator: VpnDnsPolicyCoordinator
                        get() = error("Stale telemetry must not change the resolver")
                }
            val state =
                object : VpnTelemetryStateAccess {
                    override fun status() = ServiceStatus.Connected

                    override fun stopping() = false

                    override fun runtimeSession() = activeSession

                    override fun currentLocalProxyEndpoint(): LocalProxyEndpoint? = null

                    override fun currentNetworkHandoverState(): String? = null

                    override fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot) = snapshot
                }
            val callbacks =
                object : VpnTunnelRefreshCallbacks {
                    override fun beginDnsRefresh(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) = error("Unexpected refresh")

                    override fun confirmDnsRefresh(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) = error("Unexpected refresh")

                    override suspend fun recomposeRuntimeForPolicyChange(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) = error("Unexpected refresh")

                    override fun updateRuntimeDnsState(
                        session: VpnRuntimeSession,
                        resolution: ConnectionPolicyResolution,
                    ) = error("Unexpected refresh")

                    override suspend fun failTunnelRefresh(
                        session: VpnRuntimeSession,
                        error: Exception,
                    ): Unit = throw AssertionError("Unexpected failure", error)
                }
            val coordinator = VpnTunnelRefreshCoordinator(dependencies, state, callbacks)
            val idle = NativeRuntimeSnapshot.idle("tunnel")
            val telemetry =
                VpnTelemetrySnapshot(
                    idle,
                    RuntimeTelemetryStatus.NoData,
                    idle,
                    RuntimeTelemetryStatus.NoData,
                    idle,
                    RuntimeTelemetryStatus.NoData,
                    idle,
                    RuntimeTelemetryStatus.NoData,
                    idle,
                    RuntimeTelemetryStatus.NoData,
                )
            val recovery = async { coordinator.recoverIfNeeded(oldSession, telemetry) }
            runCurrent()
            assertFalse("Recovery must wait while a refresh owns the tunnel", recovery.isCompleted)
            // A refresh can replace the runtime before it releases ownership.
            activeSession = VpnRuntimeSession("replacement")
            refreshMutex.unlock()
            assertFalse(recovery.await())
        }
}
