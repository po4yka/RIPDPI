package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.ech.EchMode
import com.poyka.ripdpi.diagnostics.ech.NscDomainEncryptionGenerator
import com.poyka.ripdpi.diagnostics.ech.NscStrategyPackOverride
import org.junit.Assert.assertEquals
import org.junit.Test

class NscStrategyPackOverrideTest {
    @Test
    fun `override replaces mode for specified domain`() {
        val base =
            mapOf(
                "a.example.com" to EchMode.Opportunistic,
                "b.example.com" to EchMode.Enabled,
            )
        val overrides =
            NscStrategyPackOverride(
                domainModes = mapOf("a.example.com" to EchMode.Disabled),
            )
        val result = NscDomainEncryptionGenerator.applyOverride(base, overrides)
        assertEquals(EchMode.Disabled, result["a.example.com"])
        assertEquals(EchMode.Enabled, result["b.example.com"])
    }

    @Test
    fun `override can add new domain not present in base`() {
        val base = mapOf("a.example.com" to EchMode.Opportunistic)
        val overrides =
            NscStrategyPackOverride(
                domainModes = mapOf("new.example.com" to EchMode.Enabled),
            )
        val result = NscDomainEncryptionGenerator.applyOverride(base, overrides)
        assertEquals(EchMode.Opportunistic, result["a.example.com"])
        assertEquals(EchMode.Enabled, result["new.example.com"])
    }

    @Test
    fun `empty override leaves base unchanged`() {
        val base =
            mapOf(
                "a.example.com" to EchMode.Enabled,
                "b.example.com" to EchMode.Opportunistic,
            )
        val overrides = NscStrategyPackOverride(domainModes = emptyMap())
        val result = NscDomainEncryptionGenerator.applyOverride(base, overrides)
        assertEquals(base, result)
    }

    @Test
    fun `override can set all three modes`() {
        val base =
            mapOf(
                "a.com" to EchMode.Opportunistic,
                "b.com" to EchMode.Opportunistic,
                "c.com" to EchMode.Opportunistic,
            )
        val overrides =
            NscStrategyPackOverride(
                domainModes =
                    mapOf(
                        "a.com" to EchMode.Enabled,
                        "b.com" to EchMode.Disabled,
                        "c.com" to EchMode.Opportunistic,
                    ),
            )
        val result = NscDomainEncryptionGenerator.applyOverride(base, overrides)
        assertEquals(EchMode.Enabled, result["a.com"])
        assertEquals(EchMode.Disabled, result["b.com"])
        assertEquals(EchMode.Opportunistic, result["c.com"])
    }
}
