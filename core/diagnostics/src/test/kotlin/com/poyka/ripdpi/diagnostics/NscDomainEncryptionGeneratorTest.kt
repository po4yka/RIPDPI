package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.ech.EchMode
import com.poyka.ripdpi.diagnostics.ech.NscDomainEncryptionGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NscDomainEncryptionGeneratorTest {
    @Test
    fun `empty domain map produces base config with no domain entries`() {
        val xml = NscDomainEncryptionGenerator.generate(emptyMap())
        assertTrue(xml.contains("<network-security-config>"))
        assertTrue(xml.contains("</network-security-config>"))
        assertTrue(!xml.contains("<domain-config"))
    }

    @Test
    fun `enabled mode emits domainEncryption mode enabled`() {
        val xml =
            NscDomainEncryptionGenerator.generate(
                mapOf("owned.example.com" to EchMode.Enabled),
            )
        assertTrue(xml.contains("<domainEncryption mode=\"enabled\""))
        assertTrue(xml.contains("owned.example.com"))
    }

    @Test
    fun `disabled mode emits domainEncryption mode disabled`() {
        val xml =
            NscDomainEncryptionGenerator.generate(
                mapOf("blocked.example.com" to EchMode.Disabled),
            )
        assertTrue(xml.contains("<domainEncryption mode=\"disabled\""))
        assertTrue(xml.contains("blocked.example.com"))
    }

    @Test
    fun `opportunistic policy uses the documented enabled XML mode`() {
        val xml =
            NscDomainEncryptionGenerator.generate(
                mapOf("other.example.com" to EchMode.Opportunistic),
            )
        assertTrue(xml.contains("<domainEncryption mode=\"enabled\""))
        assertTrue(xml.contains("other.example.com"))
    }

    @Test
    fun `multiple domains produce one domain-config block each`() {
        val domains =
            mapOf(
                "a.example.com" to EchMode.Enabled,
                "b.example.com" to EchMode.Opportunistic,
                "c.example.com" to EchMode.Disabled,
            )
        val xml = NscDomainEncryptionGenerator.generate(domains)
        assertEquals(3, xml.split("<domain-config").size - 1)
        assertTrue(xml.contains("a.example.com"))
        assertTrue(xml.contains("b.example.com"))
        assertTrue(xml.contains("c.example.com"))
    }

    @Test
    fun `output is well-formed xml with single root element`() {
        val xml =
            NscDomainEncryptionGenerator.generate(
                mapOf("x.example.com" to EchMode.Enabled),
            )
        val openCount = xml.split("<network-security-config").size - 1
        val closeCount = xml.split("</network-security-config>").size - 1
        assertEquals(1, openCount)
        assertEquals(1, closeCount)
    }

    @Test
    fun `reality owned domains map to enabled by default classifier`() {
        val ownedDomains = listOf("api.ripdpi.com", "update.ripdpi.com")
        val modeMap =
            NscDomainEncryptionGenerator.classifyDomains(
                domains = ownedDomains,
                ownedStackDomains = setOf("api.ripdpi.com", "update.ripdpi.com"),
            )
        assertEquals(EchMode.Enabled, modeMap["api.ripdpi.com"])
        assertEquals(EchMode.Enabled, modeMap["update.ripdpi.com"])
    }

    @Test
    fun `non-owned domains map to opportunistic by default classifier`() {
        val modeMap =
            NscDomainEncryptionGenerator.classifyDomains(
                domains = listOf("google.com", "example.org"),
                ownedStackDomains = setOf("api.ripdpi.com"),
            )
        assertEquals(EchMode.Opportunistic, modeMap["google.com"])
        assertEquals(EchMode.Opportunistic, modeMap["example.org"])
    }
}
