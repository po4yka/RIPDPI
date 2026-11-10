package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasquePrivacyPassProviderTest {
    private fun providerAuthFixture(): String = listOf("provider", "header").joinToString("-")

    @Test
    fun `provider is unavailable when provider URL is missing`() {
        val provider =
            BuildConfigMasquePrivacyPassProvider(
                MasquePrivacyPassProviderSettings(
                    providerUrl = "",
                ),
            )

        assertFalse(provider.isAvailable())
        assertEquals(MasquePrivacyPassBuildStatus.MissingProviderUrl, provider.buildStatus())
        assertEquals(
            MasquePrivacyPassReadiness.MissingProviderUrl,
            provider.readinessFor(
                config = privacyPassConfig(),
                credentials = privacyPassCredentials(),
            ),
        )
    }

    @Test
    fun `provider rejects invalid provider URL`() {
        val provider =
            BuildConfigMasquePrivacyPassProvider(
                MasquePrivacyPassProviderSettings(
                    providerUrl = "not a url",
                ),
            )

        assertEquals(MasquePrivacyPassBuildStatus.InvalidProviderUrl, provider.buildStatus())
        assertEquals(
            MasquePrivacyPassReadiness.InvalidProviderUrl,
            provider.readinessFor(
                config = privacyPassConfig(),
                credentials = privacyPassCredentials(),
            ),
        )
    }

    @Test
    fun `provider accepts plain http only for loopback hosts`() {
        fun status(url: String): MasquePrivacyPassBuildStatus =
            BuildConfigMasquePrivacyPassProvider(MasquePrivacyPassProviderSettings(providerUrl = url)).buildStatus()

        listOf("http://127.0.0.1:8080/t", "http://localhost/t", "http://[::1]:9/t", "https://provider.example/t")
            .forEach { assertEquals(it, MasquePrivacyPassBuildStatus.Available, status(it)) }
        listOf("http://provider.example/t", "ftp://localhost/t")
            .forEach { assertEquals(it, MasquePrivacyPassBuildStatus.InvalidProviderUrl, status(it)) }
    }

    @Test
    fun `provider resolves runtime config when build settings are valid`() {
        val providerAuthToken = providerAuthFixture()
        val provider =
            BuildConfigMasquePrivacyPassProvider(
                MasquePrivacyPassProviderSettings(
                    providerUrl = "https://provider.example/token",
                    providerAuthToken = providerAuthToken,
                ),
            )

        assertTrue(provider.isAvailable())
        assertEquals(MasquePrivacyPassBuildStatus.Available, provider.buildStatus())
        var resolved: MasquePrivacyPassRuntimeConfig? = null
        kotlinx.coroutines.test.runTest {
            resolved =
                provider.resolve(
                    profileId = "edge",
                    config = privacyPassConfig(),
                    credentials = privacyPassCredentials(),
                )
        }

        assertEquals("https://provider.example/token", resolved?.providerUrl)
        assertEquals(providerAuthToken, resolved?.providerAuthToken)
    }

    @Test
    fun `provider does not resolve for non privacy pass auth mode`() {
        val provider =
            BuildConfigMasquePrivacyPassProvider(
                MasquePrivacyPassProviderSettings(
                    providerUrl = "https://provider.example/token",
                ),
            )

        val readiness =
            provider.readinessFor(
                config = privacyPassConfig(),
                credentials = RelayCredentialRecord(profileId = "edge"),
            )
        var resolved: MasquePrivacyPassRuntimeConfig? = null
        kotlinx.coroutines.test.runTest {
            resolved =
                provider.resolve(
                    profileId = "edge",
                    config = privacyPassConfig(),
                    credentials = RelayCredentialRecord(profileId = "edge"),
                )
        }

        assertEquals(MasquePrivacyPassReadiness.UnsupportedAuthMode, readiness)
        assertNull(resolved)
    }

    @Test
    fun `provider does not resolve for non masque relay kind`() {
        val provider =
            BuildConfigMasquePrivacyPassProvider(
                MasquePrivacyPassProviderSettings(
                    providerUrl = "https://provider.example/token",
                ),
            )

        val readiness =
            provider.readinessFor(
                config = privacyPassConfig().copy(kind = RelayKindVlessReality),
                credentials = privacyPassCredentials(),
            )

        assertEquals(MasquePrivacyPassReadiness.UnsupportedRelayKind, readiness)
    }

    private fun privacyPassConfig(): RipDpiRelayConfig =
        RipDpiRelayConfig(
            enabled = true,
            kind = RelayKindMasque,
            profileId = "edge",
            masqueUrl = "https://masque.example/",
        )

    private fun privacyPassCredentials(): RelayCredentialRecord =
        RelayCredentialRecord(
            profileId = "edge",
            masqueAuthMode = RelayMasqueAuthModePrivacyPass,
        )
}
