package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.FailoverPolicy
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.data.subscription.toSelectorFailover
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadSelectorTopologyTest {
    @Test
    fun `nested selector keeps payload policy for the selected root`() {
        val result = SelectorUrltestGroupImport.import(bundle(), "nested")
        assertTrue("$result", result is SelectorUrltestImportResult.Success)
        result as SelectorUrltestImportResult.Success
        assertEquals(listOf("core"), result.group?.members?.map { it.displayName })
        assertEquals("select", (result.failoverPolicy as FailoverPolicy.Payload).policy.selectorTag)
        assertEquals(result.failoverPolicy.toSelectorFailover(), result.group?.failover)
    }

    @Test
    fun `payload policy cannot hide unsupported nested probe topology`() {
        val extra = """{"type":"urltest","tag":"second","outbounds":["core"],"url":"https://probe.test/204"}"""
        assertError(
            bundle()
                .replaceFirst("\"outbounds\":[", "\"outbounds\":[$extra,")
                .replace("\"outbounds\":[\"auto\"]", "\"outbounds\":[\"auto\",\"second\"]"),
        )
    }

    @Test
    fun `payload policy for a nested selector cannot replace the root policy`() {
        assertError(bundle().replace("\"selector_tag\":\"select\"", "\"selector_tag\":\"nested\""))
    }

    @Test
    fun `device only AWG bundle rejects payload policy instead of silently dropping it`() {
        val extension = resource("/contract/ripdpi-bundle.example.json")
        val awg = extension.getValue("amneziawg")
        val source = """{"outbounds":[
          {"type":"selector","tag":"select","outbounds":["direct"]},
          {"type":"direct","tag":"direct"}
        ],"route":{"final":"select"},"ripdpi":{"schema_version":1,"amneziawg":$awg}}
        """
        val withoutPolicy = SelectorUrltestGroupImport.import(source, "awg")
        assertTrue("$withoutPolicy", withoutPolicy is SelectorUrltestImportResult.Success)
        assertEquals(null, (withoutPolicy as SelectorUrltestImportResult.Success).group)
        val parsed = Json.parseToJsonElement(source).jsonObject
        val withPolicy =
            JsonObject(
                parsed +
                    ("ripdpi" to JsonObject(parsed.getValue("ripdpi").jsonObject + ("payload_failover" to policy()))),
            )
        assertError(withPolicy.toString())
    }

    private fun assertError(payload: String) {
        val result = SelectorUrltestGroupImport.import(payload, "fixture")
        assertTrue("$result", result is SelectorUrltestImportResult.Error)
    }

    private fun policy() =
        resource(
            "/contract/ripdpi-bundle.payload-failover.json",
        ).getValue("ripdpi").jsonObject.getValue("payload_failover")

    private fun resource(path: String) =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource(path)).readText()).jsonObject

    private fun bundle(): String =
        """{"outbounds":[
          {"type":"trojan","tag":"core","server":"core.example","server_port":443,"password":"fixture"},
          {"type":"selector","tag":"select","outbounds":["nested"]},
          {"type":"selector","tag":"nested","outbounds":["auto"]},
          {"type":"urltest","tag":"auto","outbounds":["core"],"url":"https://probe.test/204"}
        ],"route":{"final":"select"},"ripdpi":{"schema_version":1,"payload_failover":${policy()}}}
        """
}
