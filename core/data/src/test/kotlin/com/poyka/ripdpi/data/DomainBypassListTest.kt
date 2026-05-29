package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.rules.DomainBypassList
import com.poyka.ripdpi.data.rules.OutboundTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBypassListTest {
    @Test
    fun `bare host compiles to verbatim exact match`() {
        val result = DomainBypassList.compile("example.com")
        assertEquals(listOf("example.com"), result.cleanLines)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `leading-dot host canonicalises to domain_suffix`() {
        val result = DomainBypassList.compile(".example.com")
        assertEquals(listOf("domain_suffix:.example.com"), result.cleanLines)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `explicit prefixes pass through verbatim`() {
        val raw =
            """
            domain:exact.example.com
            domain_suffix:bank.example.com
            domain_regex:^api\..*\.example\.com$
            geosite:category-ru
            """.trimIndent()
        val result = DomainBypassList.compile(raw)
        assertEquals(
            listOf(
                "domain:exact.example.com",
                "domain_suffix:bank.example.com",
                "domain_regex:^api\\..*\\.example\\.com$",
                "geosite:category-ru",
            ),
            result.cleanLines,
        )
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `blank lines are ignored and entries trimmed`() {
        val raw = "\n  example.com  \n\n   \n.gov.ru\n"
        val result = DomainBypassList.compile(raw)
        assertEquals(listOf("example.com", "domain_suffix:.gov.ru"), result.cleanLines)
    }

    @Test
    fun `duplicate canonical lines are de-duplicated keeping first`() {
        val raw = "example.com\nexample.com\n.x.com\n.x.com"
        val result = DomainBypassList.compile(raw)
        assertEquals(listOf("example.com", "domain_suffix:.x.com"), result.cleanLines)
    }

    @Test
    fun `malformed regex surfaces inline error and other lines still save`() {
        val raw = "good.com\ndomain_regex:^(unclosed\nother.com"
        val result = DomainBypassList.compile(raw)
        assertEquals(listOf("good.com", "other.com"), result.cleanLines)
        assertEquals(1, result.errors.size)
        assertEquals(2, result.errors[0].lineNumber)
        assertTrue(result.errors[0].message.contains("regex", ignoreCase = true))
    }

    @Test
    fun `domain with spaces is rejected with inline error`() {
        val raw = "valid.com\nnot a domain"
        val result = DomainBypassList.compile(raw)
        assertEquals(listOf("valid.com"), result.cleanLines)
        assertEquals(1, result.errors.size)
        assertEquals(2, result.errors[0].lineNumber)
    }

    @Test
    fun `empty regex and empty geosite are rejected`() {
        val result = DomainBypassList.compile("domain_regex:\ngeosite:")
        assertTrue(result.cleanLines.isEmpty())
        assertEquals(2, result.errors.size)
    }

    @Test
    fun `compileToRule produces a managed bypass rule with highest priority`() {
        val rule = DomainBypassList.compileToRule("bank.ru\n.gov.ru", existingId = 7L)
        requireNotNull(rule)
        assertEquals(7L, rule.id)
        assertEquals(DomainBypassList.ManagedBypassRuleName, rule.name)
        assertEquals(DomainBypassList.ManagedBypassRuleUserOrder, rule.userOrder)
        assertTrue(rule.userOrder < 0)
        assertEquals(OutboundTag.Bypass, rule.outboundTag)
        assertEquals("bank.ru\ndomain_suffix:.gov.ru", rule.domains)
        assertTrue(rule.enabled)
        assertTrue(DomainBypassList.isManagedBypassRule(rule))
    }

    @Test
    fun `compileToRule returns null when no clean entries`() {
        assertNull(DomainBypassList.compileToRule("   \n\n"))
        assertNull(DomainBypassList.compileToRule("domain_regex:^(bad"))
    }

    @Test
    fun `toEditorText round-trips the compiled rule domains`() {
        val text = "bank.ru\n.gov.ru\ndomain_regex:^api\\.x$"
        val rule = DomainBypassList.compileToRule(text)
        assertEquals(rule?.domains, DomainBypassList.toEditorText(rule))
    }
}
