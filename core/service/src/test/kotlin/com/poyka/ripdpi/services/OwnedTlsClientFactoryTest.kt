package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.InMemoryStrategyPackStateStore
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeDesktopStable
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxEchStable
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxStable
import okhttp3.CipherSuite
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedTlsClientFactoryTest {
    @Test
    fun `apply tls fingerprint profile maps desktop and ech variants onto browser cipher sets`() {
        val chromeDesktop =
            OkHttpClient.Builder()
                .applyTlsFingerprintProfile(TlsFingerprintProfileChromeDesktopStable)
                .build()
                .connectionSpecs
                .first()
        val firefoxEch =
            OkHttpClient.Builder()
                .applyTlsFingerprintProfile(TlsFingerprintProfileFirefoxEchStable)
                .build()
                .connectionSpecs
                .first()

        assertEquals(
            listOf(
                CipherSuite.TLS_AES_128_GCM_SHA256,
                CipherSuite.TLS_AES_256_GCM_SHA384,
                CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            ),
            chromeDesktop.cipherSuites,
        )
        assertEquals(
            listOf(
                CipherSuite.TLS_AES_128_GCM_SHA256,
                CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            ),
            firefoxEch.cipherSuites,
        )
    }

    @Test
    fun `selection for authority stays within expanded browser family set`() {
        val stateStore =
            InMemoryStrategyPackStateStore().apply {
                update(
                    StrategyPackRuntimeState(
                        tlsProfileSetId = "browser_family_v2",
                        tlsProfileAllowedIds =
                            listOf(
                                TlsFingerprintProfileChromeStable,
                                TlsFingerprintProfileChromeDesktopStable,
                                TlsFingerprintProfileFirefoxStable,
                                TlsFingerprintProfileFirefoxEchStable,
                            ),
                        tlsRotationEnabled = true,
                    ),
                )
            }
        val factory =
            DefaultOwnedTlsClientFactory(
                profileProvider = FakeOwnedTlsFingerprintProfileProvider(TlsFingerprintProfileChromeStable),
                strategyPackStateStore = stateStore,
                sessionSeed = 42L,
            )

        val selection = factory.selectionForAuthority("video.example")
        val repeated = factory.selectionForAuthority("video.example")

        assertEquals(selection, repeated)
        assertTrue(
            selection.profileId in
                listOf(
                    TlsFingerprintProfileChromeStable,
                    TlsFingerprintProfileChromeDesktopStable,
                    TlsFingerprintProfileFirefoxStable,
                    TlsFingerprintProfileFirefoxEchStable,
                ),
        )
        assertEquals("browser_family_v2", selection.profileSetId)
    }

    private class FakeOwnedTlsFingerprintProfileProvider(
        private val profileId: String,
    ) : OwnedTlsFingerprintProfileProvider {
        override fun currentProfile(): String = profileId
    }
}
