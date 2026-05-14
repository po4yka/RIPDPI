package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.FailoverPolicy
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SelectorUrltestGroupImport]: promotes a sing-box
 * `selector` + `urltest` outbound pair into a RIPDPI [ProxyGroup] with a
 * failover policy.
 */
class SelectorUrltestGroupImportTest {
    private val groupId = "sub-fleet"

    private fun fullBundle(): String =
        """
        {
          "outbounds": [
            { "type": "vless", "tag": "p0-reality-upcloud-prod", "server": "p0.example.com",
              "server_port": 443, "uuid": "00000000-0000-0000-0000-000000000000" },
            { "type": "hysteria2", "tag": "p2-hysteria2-upcloud-prod", "server": "p2.example.com",
              "server_port": 8443, "password": "hy2-secret" },
            { "type": "vless", "tag": "p1-xhttp-hetzner-prod", "server": "p1.example.com",
              "server_port": 443, "uuid": "11111111-1111-1111-1111-111111111111" },
            { "type": "selector", "tag": "select",
              "outbounds": ["p0-reality-upcloud-prod","p2-hysteria2-upcloud-prod","p1-xhttp-hetzner-prod","auto"],
              "default": "auto", "interrupt_exist_connections": false },
            { "type": "urltest", "tag": "auto",
              "outbounds": ["p0-reality-upcloud-prod","p2-hysteria2-upcloud-prod","p1-xhttp-hetzner-prod"],
              "url": "https://www.gstatic.com/generate_204",
              "interval": "5m", "tolerance": 50 }
          ]
        }
        """.trimIndent()

    private fun success(result: SelectorUrltestImportResult): SelectorUrltestImportResult.Success {
        assertTrue("expected success, got $result", result is SelectorUrltestImportResult.Success)
        return result as SelectorUrltestImportResult.Success
    }

    @Test
    fun `full bundle imports to three profiles and one selector group with urltest policy`() {
        val imported = success(SelectorUrltestGroupImport.import(fullBundle(), groupId))

        assertEquals(3, imported.profiles.size)
        val group = imported.group
        assertTrue(group != null)
        group!!
        assertTrue(group.isSelector)
        // member order matches selector outbounds[] minus the urltest tag
        assertEquals(
            listOf("p0-reality-upcloud-prod", "p2-hysteria2-upcloud-prod", "p1-xhttp-hetzner-prod"),
            imported.memberOrder,
        )
        assertEquals("auto", imported.defaultMemberTag)

        val policy = imported.failoverPolicy
        assertTrue(policy is FailoverPolicy.Urltest)
        policy as FailoverPolicy.Urltest
        assertEquals("https://www.gstatic.com/generate_204", policy.probeUrl)
        assertEquals(300, policy.intervalSeconds)
        assertEquals(50, policy.toleranceMs)
    }

    @Test
    fun `forward reference - selector listed before the outbounds it names still resolves`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "selector", "tag": "select",
                  "outbounds": ["p0", "p1", "auto"], "default": "auto" },
                { "type": "urltest", "tag": "auto", "outbounds": ["p0", "p1"],
                  "url": "https://probe.example.com/204", "interval": "1m", "tolerance": 30 },
                { "type": "vless", "tag": "p0", "server": "p0.example.com",
                  "server_port": 443, "uuid": "00000000-0000-0000-0000-000000000000" },
                { "type": "trojan", "tag": "p1", "server": "p1.example.com",
                  "server_port": 443, "password": "secret" }
              ]
            }
            """.trimIndent()

        val imported = success(SelectorUrltestGroupImport.import(json, groupId))

        assertEquals(2, imported.profiles.size)
        assertEquals(listOf("p0", "p1"), imported.memberOrder)
        assertTrue(imported.failoverPolicy is FailoverPolicy.Urltest)
        assertEquals(60, (imported.failoverPolicy as FailoverPolicy.Urltest).intervalSeconds)
    }

    @Test
    fun `selector only - no urltest yields a group with a manual failover policy`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "vless", "tag": "p0", "server": "p0.example.com",
                  "server_port": 443, "uuid": "00000000-0000-0000-0000-000000000000" },
                { "type": "trojan", "tag": "p1", "server": "p1.example.com",
                  "server_port": 443, "password": "secret" },
                { "type": "selector", "tag": "select",
                  "outbounds": ["p0", "p1"], "default": "p0" }
              ]
            }
            """.trimIndent()

        val imported = success(SelectorUrltestGroupImport.import(json, groupId))

        assertEquals(2, imported.profiles.size)
        assertTrue(imported.group != null)
        assertEquals(listOf("p0", "p1"), imported.memberOrder)
        assertEquals("p0", imported.defaultMemberTag)
        assertEquals(FailoverPolicy.Manual, imported.failoverPolicy)
    }

    @Test
    fun `single concrete outbound and no selector yields one profile and no group`() {
        val json =
            """
            { "type": "trojan", "tag": "solo", "server": "solo.example.com",
              "server_port": 443, "password": "secret" }
            """.trimIndent()

        val imported = success(SelectorUrltestGroupImport.import(json, groupId))

        assertEquals(1, imported.profiles.size)
        assertNull(imported.group)
        assertTrue(imported.memberOrder.isEmpty())
    }

    @Test
    fun `urltest present but no selector skips the orphan and creates no group`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "vless", "tag": "p0", "server": "p0.example.com",
                  "server_port": 443, "uuid": "00000000-0000-0000-0000-000000000000" },
                { "type": "urltest", "tag": "auto", "outbounds": ["p0"],
                  "url": "https://probe.example.com/204", "interval": "5m", "tolerance": 50 }
              ]
            }
            """.trimIndent()

        val imported = success(SelectorUrltestGroupImport.import(json, groupId))

        assertEquals(1, imported.profiles.size)
        assertNull(imported.group)
    }

    @Test
    fun `selector naming a missing tag is rejected with a typed error naming the tag`() {
        val json =
            """
            {
              "outbounds": [
                { "type": "vless", "tag": "p0", "server": "p0.example.com",
                  "server_port": 443, "uuid": "00000000-0000-0000-0000-000000000000" },
                { "type": "selector", "tag": "select",
                  "outbounds": ["p0", "p-missing"], "default": "p0" }
              ]
            }
            """.trimIndent()

        val result = SelectorUrltestGroupImport.import(json, groupId)

        assertTrue(result is SelectorUrltestImportResult.Error)
        result as SelectorUrltestImportResult.Error
        assertTrue(result.message.contains("p-missing"))
    }

    @Test
    fun `malformed json surfaces a typed error`() {
        val result = SelectorUrltestGroupImport.import("{ not json ", groupId)

        assertTrue(result is SelectorUrltestImportResult.Error)
    }

    @Test
    fun `generated group is a subscription selector group carrying the supplied id`() {
        val imported = success(SelectorUrltestGroupImport.import(fullBundle(), groupId))

        val group = imported.group!!
        assertEquals(groupId, group.id)
        assertEquals(ProxyGroupType.SUBSCRIPTION, group.type)
        assertTrue(group.isSelector)
        assertTrue(imported.profiles.all { it.groupId == groupId })
    }
}
