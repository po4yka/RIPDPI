package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTlsSettingsTest {
    @Test
    fun `normalizeFakeTlsSniMode returns known mode unchanged`() {
        assertEquals(FakeTlsSniModeFixed, normalizeFakeTlsSniMode("fixed"))
        assertEquals(FakeTlsSniModeRandomized, normalizeFakeTlsSniMode("randomized"))
    }

    @Test
    fun `normalizeFakeTlsSniMode lowercases and trims input`() {
        assertEquals(FakeTlsSniModeFixed, normalizeFakeTlsSniMode("  FIXED  "))
        assertEquals(FakeTlsSniModeRandomized, normalizeFakeTlsSniMode("RANDOMIZED"))
    }

    @Test
    fun `normalizeFakeTlsSniMode falls back to fixed for unknown`() {
        assertEquals(FakeTlsSniModeFixed, normalizeFakeTlsSniMode("unknown"))
        assertEquals(FakeTlsSniModeFixed, normalizeFakeTlsSniMode(""))
    }

    @Test
    fun `default settings have no custom fake tls profile`() {
        val settings = AppSettings.newBuilder().build()
        assertFalse(settings.hasCustomFakeTlsProfile())
    }

    @Test
    fun `fakeTlsUseOriginal triggers custom profile`() {
        val settings = AppSettings.newBuilder().setFakeTlsUseOriginal(true).build()
        assertTrue(settings.hasCustomFakeTlsProfile())
    }

    @Test
    fun `fakeTlsRandomize triggers custom profile`() {
        val settings = AppSettings.newBuilder().setFakeTlsRandomize(true).build()
        assertTrue(settings.hasCustomFakeTlsProfile())
    }

    @Test
    fun `fakeTlsDupSessionId triggers custom profile`() {
        val settings = AppSettings.newBuilder().setFakeTlsDupSessionId(true).build()
        assertTrue(settings.hasCustomFakeTlsProfile())
    }

    @Test
    fun `fakeTlsPadEncap triggers custom profile`() {
        val settings = AppSettings.newBuilder().setFakeTlsPadEncap(true).build()
        assertTrue(settings.hasCustomFakeTlsProfile())
    }

    @Test
    fun `nonzero fakeTlsSize triggers custom profile`() {
        val settings = AppSettings.newBuilder().setFakeTlsSize(192).build()
        assertTrue(settings.hasCustomFakeTlsProfile())
    }

    @Test
    fun `randomized sni mode triggers custom profile`() {
        val settings = AppSettings.newBuilder().setFakeTlsSniMode(FakeTlsSniModeRandomized).build()
        assertTrue(settings.hasCustomFakeTlsProfile())
    }

    @Test
    fun `custom fakeSni with fixed mode triggers custom profile`() {
        val settings =
            AppSettings
                .newBuilder()
                .setFakeTlsSniMode(FakeTlsSniModeFixed)
                .setFakeSni("custom.example.com")
                .build()
        assertTrue(settings.hasCustomFakeTlsProfile())
    }

    @Test
    fun `default fakeSni with fixed mode does not trigger custom profile`() {
        val settings =
            AppSettings
                .newBuilder()
                .setFakeTlsSniMode(FakeTlsSniModeFixed)
                .setFakeSni(DefaultFakeSni)
                .build()
        assertFalse(settings.hasCustomFakeTlsProfile())
    }
}
