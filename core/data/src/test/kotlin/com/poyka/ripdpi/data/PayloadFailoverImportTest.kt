package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.FailoverPolicy
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadFailoverImportTest {
    private fun fixture() =
        requireNotNull(javaClass.getResource("/contract/ripdpi-bundle.payload-failover.json")).readText()

    @Test
    fun `actual deployer bundle imports and persists strict policy`() {
        val imported = SelectorUrltestGroupImport.import(fixture(), "fixture") as SelectorUrltestImportResult.Success
        val policy = (imported.failoverPolicy as FailoverPolicy.Payload).policy
        val group = requireNotNull(imported.group)
        assertEquals(2, group.members.size)
        assertEquals(32_768, policy.probes.first().expectedBytes)
        assertEquals(policy, group.failover!!.payloadPolicy)
        val decoded = Json.decodeFromString<ProxyGroup>(Json.encodeToString(group))
        assertEquals(policy, decoded.failover!!.payloadPolicy)
    }

    @Test
    fun `invalid policy cannot silently fall back to urltest`() {
        val source = fixture()
        val invalid =
            listOf(
                source.replace("\"expected_bytes\": 32768", "\"expected_bytes\": 0"),
                source.replace("\"failure_threshold\": 2", "\"failure_threshold\": 1"),
                source.replace("\"timeout_seconds\": 10", "\"timeout_seconds\": 31"),
                source.replace("\"selector_tag\": \"select\"", "\"selector_tag\": \"missing\""),
                source.replace("control-b.example.test", "CONTROL-A.example.test.:443"),
                source.replace("\"recovery_threshold\": 2,", ""),
                source.replace("\"max_backoff_seconds\": 900", "\"max_backoff_seconds\": 900, \"unknown\": true"),
            )
        invalid.forEach {
            assertTrue(
                SelectorUrltestGroupImport.import(it, "fixture") is SelectorUrltestImportResult.Error,
            )
        }
    }

    @Test
    fun `unsafe or ambiguous control urls are rejected`() {
        val rejected =
            listOf(
                "http://control.test/p",
                "https://user@control.test/p",
                "https://control.test/p#fragment",
                "https://control.test:0/p",
                "https://control.test/ bad",
                "https://control.test/\\bad",
                "https://127.1/p",
                "https://0177.0.0.1/p",
                "https://0x7f000001/p",
                "https://[fe80::1%25en0]/p",
            )
        rejected.forEach { url ->
            assertTrue(runCatching { PayloadProbeControl(url, 32_768, "a".repeat(64)) }.isFailure)
        }
    }

    @Test
    fun `canonical IPv6 aliases cannot supply two independent origins`() {
        val source =
            fixture()
                .replace("control-a.example.test", "[2001:db8::1]")
                .replace("control-b.example.test", "[2001:0db8:0:0:0:0:0:1]:443")
        assertTrue(SelectorUrltestGroupImport.import(source, "fixture") is SelectorUrltestImportResult.Error)
    }
}
