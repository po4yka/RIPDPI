package com.poyka.ripdpi.e2e

import com.poyka.ripdpi.core.DefaultTun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.TunForwardingEvidence
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Observes the real native bridge; never substitutes a session or rewrites its inputs. */
internal class ObservedNativeTunnelBridgeFactory(
    private val delegate: DefaultTun2SocksBridgeFactory,
) : Tun2SocksBridgeFactory {
    private val generations = AtomicLong()
    private val active = AtomicReference<ActiveBridge?>()

    override fun create(): Tun2SocksBridge {
        val actual = delegate.create()
        val started = AtomicReference<ActiveBridge?>()
        return object : Tun2SocksBridge by actual {
            override suspend fun start(
                config: Tun2SocksConfig,
                tunFd: Int,
                flowAttributionBridge: Any?,
            ) {
                actual.start(config, tunFd, flowAttributionBridge)
                val observation =
                    ActiveBridge(actual, generations.incrementAndGet(), config.uidPolicyMode, config.uidPolicyUids.size)
                started.set(observation)
                active.set(observation)
            }

            override suspend fun stop() {
                val observation = started.get()
                actual.stop()
                if (observation != null) {
                    active.compareAndSet(observation, null)
                    started.compareAndSet(observation, null)
                }
            }
        }
    }

    suspend fun observe(): NativeTunnelObservation? {
        val before = active.get() ?: return null
        val evidence = runCatching { before.bridge.forwardingEvidence() }.getOrNull()
        if (active.get() !== before) return null
        return NativeTunnelObservation(before.generation, before.uidMode, before.uidCount, evidence)
    }

    private class ActiveBridge(
        val bridge: Tun2SocksBridge,
        val generation: Long,
        val uidMode: String,
        val uidCount: Int,
    )
}

internal data class NativeTunnelObservation(
    val generation: Long,
    val configuredUidMode: String,
    val configuredUidCount: Int,
    val forwarding: TunForwardingEvidence?,
)
