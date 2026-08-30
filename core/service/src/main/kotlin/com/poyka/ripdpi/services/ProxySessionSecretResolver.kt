package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.withCloudflareWorkerTransport
import com.poyka.ripdpi.data.WsTunnelWorkerCredentialStore
import com.poyka.ripdpi.data.resolveTransport
import com.poyka.ripdpi.proto.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxySessionSecretResolver
    @Inject
    constructor(
        private val credentialStore: WsTunnelWorkerCredentialStore,
    ) {
        suspend fun currentBearer(settings: AppSettings): String? =
            credentialStore.resolveTransport(settings)?.authBearer?.value

        suspend fun applyRemembered(
            preferences: RipDpiProxyUIPreferences,
            currentSettings: AppSettings,
        ): RipDpiProxyUIPreferences {
            val rememberedWorkerUrl =
                preferences.wsTunnel.cloudflareWorkerUrl
                    ?.trim()
                    .orEmpty()
            val rememberedCredentialRef =
                preferences.wsTunnel.cloudflareWorkerCredentialRef
                    ?.trim()
                    .orEmpty()
            require(rememberedWorkerUrl.isEmpty() == rememberedCredentialRef.isEmpty()) {
                "Remembered Cloudflare Worker URL and credential reference must be configured together"
            }
            val currentTransport = credentialStore.resolveTransport(currentSettings)
            if (currentTransport == null) {
                return preferences.withCloudflareWorkerTransport(null)
            }
            require(preferences.wsTunnel.fakeSni.isNullOrBlank()) {
                "Remembered Cloudflare Worker transport cannot be combined with WS tunnel fake SNI"
            }
            return preferences.withCloudflareWorkerTransport(currentTransport)
        }
    }
