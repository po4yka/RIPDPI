package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.service.warp.WarpBootstrapProxyRuntimePolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WarpBootstrapProxyRuntimePolicyTest {
    @Test
    fun `WARP bootstrap ignores configured Worker transport without a credential`() =
        runTest {
            val appSettingsRepository =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setWsTunnelEnabled(true)
                        .setWsTunnelMode("always")
                        .setWsTunnelWorkerUrl("https://worker.example/ws")
                        .setWsTunnelWorkerCredentialRef("missing-worker")
                        .build(),
                )

            val preferences = WarpBootstrapProxyRuntimePolicy(appSettingsRepository).preferencesFor(18080)

            assertFalse(preferences.wsTunnel.enabled)
            assertNull(preferences.wsTunnel.cloudflareWorkerUrl)
            assertNull(preferences.wsTunnel.cloudflareWorkerCredentialRef)
            assertNull(preferences.wsTunnel.cloudflareWorkerBearer)
        }
}
