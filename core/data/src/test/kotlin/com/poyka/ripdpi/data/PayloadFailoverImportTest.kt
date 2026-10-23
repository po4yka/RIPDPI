package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.FailoverPolicy
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import com.poyka.ripdpi.serialization.RipDpiStrictJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        assertEquals(262_144, policy.probes.first().expectedBytes)
        assertEquals(policy, group.failover!!.payloadPolicy)
        val decoded = Json.decodeFromString<ProxyGroup>(Json.encodeToString(group))
        assertEquals(policy, decoded.failover!!.payloadPolicy)
    }

    @Test
    fun `invalid policy cannot silently fall back to urltest`() {
        val source = fixture()
        val invalid =
            listOf(
                source.replace("\"schema_version\": 2", "\"schema_version\": 1"),
                source.replace("\"schema_version\": 2,", ""),
                source.replace("\"expected_bytes\": 262144", "\"expected_bytes\": 0"),
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
    fun `quality null missing fields unknown fields and incompatible budgets reject atomically`() {
        val root =
            RipDpiStrictJson.parseToJsonElement(fixture())
                as JsonObject
        val extension = root["ripdpi"] as JsonObject
        val payload = extension["payload_failover"] as JsonObject
        val quality = payload["quality"] as JsonObject
        val invalid =
            listOf(
                JsonNull,
                JsonObject(quality - "minimum_gain_percent"),
                JsonObject(quality + ("unknown" to JsonPrimitive(1))),
                JsonObject(quality + ("max_pass_bytes" to JsonPrimitive(65538))),
                JsonObject(quality + ("max_window_bytes" to JsonPrimitive(65538))),
                JsonObject(quality + ("window_seconds" to JsonPrimitive(30))),
            )
        invalid.forEach { value ->
            val changed =
                JsonObject(
                    root + (
                        "ripdpi" to
                            JsonObject(
                                extension + (
                                    "payload_failover" to
                                        JsonObject(payload + ("quality" to value))
                                ),
                            )
                    ),
                )
            assertTrue(
                SelectorUrltestGroupImport.import(changed.toString(), "fixture") is SelectorUrltestImportResult.Error,
            )
        }
    }

    @Test
    fun `health-only schema two omits quality even with encode defaults and survives import`() {
        val imported = SelectorUrltestGroupImport.import(fixture(), "fixture") as SelectorUrltestImportResult.Success
        val policy = (imported.failoverPolicy as FailoverPolicy.Payload).policy.copy(quality = null)
        val encoded = RipDpiEncodeDefaultsJson.encodeToString(policy)
        assertTrue(!encoded.contains("quality"))
        assertEquals(policy, RipDpiStrictJson.decodeFromString<PayloadFailoverPolicy>(encoded))
        val root =
            RipDpiStrictJson.parseToJsonElement(fixture())
                as JsonObject
        val extension = root["ripdpi"] as JsonObject
        val changed =
            JsonObject(
                root + (
                    "ripdpi" to
                        JsonObject(
                            extension + (
                                "payload_failover" to
                                    RipDpiStrictJson.parseToJsonElement(encoded)
                            ),
                        )
                ),
            )
        val roundtrip =
            SelectorUrltestGroupImport.import(
                changed.toString(),
                "fixture",
            ) as SelectorUrltestImportResult.Success
        assertEquals(policy, (roundtrip.failoverPolicy as FailoverPolicy.Payload).policy)
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
