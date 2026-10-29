package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiFakePacketConfig
import com.poyka.ripdpi.core.RipDpiListenConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.setGroupActivationFilterCompat
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsProxyConfigStrategyApplierTest {
    private val repository =
        InMemoryAppSettingsRepository(
            AppSettings
                .newBuilder()
                .setFakeTtl(3)
                .setProxyPort(1080)
                .setNoDomain(false)
                .build(),
        )
    private val applier = SettingsProxyConfigStrategyApplier(repository)
    private val preferences =
        RipDpiProxyUIPreferences(
            fakePackets = RipDpiFakePacketConfig(fakeTtl = 7),
            listen = RipDpiListenConfig(port = 2222),
        )

    @Test
    fun `apply writes strategy fields only and restore puts them back`() =
        runTest {
            val snapshot = applier.apply(preferences)

            assertEquals(7, repository.snapshot().fakeTtl)
            assertEquals("listen port is not a strategy field", 1080, repository.snapshot().proxyPort)

            applier.restore(snapshot)

            assertEquals(3, repository.snapshot().fakeTtl)
            assertEquals(1080, repository.snapshot().proxyPort)
        }

    @Test
    fun `restore keeps an unrelated edit made during the replay`() =
        runTest {
            val snapshot = applier.apply(preferences)
            repository.update { setNoDomain(true) }

            applier.restore(snapshot)

            assertEquals(3, repository.snapshot().fakeTtl)
            assertEquals(true, repository.snapshot().noDomain)
        }

    @Test
    fun `restore keeps a strategy field the user changed during the replay`() =
        runTest {
            val snapshot = applier.apply(preferences)
            repository.update { setFakeTtl(9) }

            applier.restore(snapshot)

            assertEquals(9, repository.snapshot().fakeTtl)
        }

    @Test
    fun `relay and warp fields are untouched by apply and restore`() =
        runTest {
            repository.update {
                setRelayServer("original-relay")
                setWarpRouteHosts("original.example")
                setWarpAmneziaJc(5)
            }
            val changed =
                RipDpiProxyUIPreferences(
                    relay = RipDpiRelayConfig(enabled = true, server = "other-relay"),
                    warp = RipDpiWarpConfig(enabled = true, routeHosts = "other.example"),
                    fakePackets = RipDpiFakePacketConfig(fakeTtl = 7),
                )

            val snapshot = applier.apply(changed)

            assertEquals(7, repository.snapshot().fakeTtl)
            assertEquals("original-relay", repository.snapshot().relayServer)
            assertFalse(repository.snapshot().relayEnabled)
            assertEquals("original.example", repository.snapshot().warpRouteHosts)
            assertEquals(5, repository.snapshot().warpAmneziaJc)
            assertFalse(repository.snapshot().warpEnabled)

            applier.restore(snapshot)

            assertEquals("original-relay", repository.snapshot().relayServer)
            assertEquals("original.example", repository.snapshot().warpRouteHosts)
        }

    @Test
    fun `restore leaves a field alone when apply did not change it`() =
        runTest {
            val snapshot = applier.apply(RipDpiProxyUIPreferences(fakePackets = RipDpiFakePacketConfig(fakeTtl = 3)))
            repository.update { setFakeTtl(9) }

            applier.restore(snapshot)

            assertEquals(9, repository.snapshot().fakeTtl)
        }

    @Test
    fun `chain lists and the group activation filter are applied and restored`() =
        runTest {
            repository.update {
                setStrategyChains(
                    tcpSteps = RipDpiChainConfig().tcpSteps,
                    udpSteps = listOf(UdpChainStepModel(count = 2)),
                )
                setGroupActivationFilterCompat(ActivationFilterModel(round = NumericRangeModel(1, 2)))
            }
            val original = repository.snapshot()
            val cleared = RipDpiProxyUIPreferences(chains = RipDpiChainConfig(udpSteps = emptyList()))

            val snapshot = applier.apply(cleared)

            assertEquals(0, repository.snapshot().udpChainStepsCount)
            assertFalse(repository.snapshot().hasGroupActivationFilter())

            applier.restore(snapshot)

            assertEquals(original.udpChainStepsList, repository.snapshot().udpChainStepsList)
            assertTrue(repository.snapshot().hasGroupActivationFilter())
            assertEquals(original.groupActivationFilter, repository.snapshot().groupActivationFilter)
            assertEquals(original.tcpChainStepsList, repository.snapshot().tcpChainStepsList)
        }

    @Test
    fun `transport fields are applied and restored`() =
        runTest {
            val transport =
                RipDpiProxyUIPreferences(
                    listen = RipDpiListenConfig(tcpFastOpen = true, customTtl = true, defaultTtl = 11),
                    protocols = RipDpiProtocolConfig(resolveDomains = false),
                )

            val snapshot = applier.apply(transport)

            assertTrue(repository.snapshot().tcpFastOpen)
            assertTrue(repository.snapshot().customTtl)
            assertEquals(11, repository.snapshot().defaultTtl)
            assertTrue(repository.snapshot().noDomain)

            applier.restore(snapshot)

            assertFalse(repository.snapshot().tcpFastOpen)
            assertFalse(repository.snapshot().customTtl)
            assertEquals(0, repository.snapshot().defaultTtl)
            assertFalse(repository.snapshot().noDomain)
        }
}

private class InMemoryAppSettingsRepository(
    initial: AppSettings,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}
