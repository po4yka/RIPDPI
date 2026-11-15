package com.poyka.ripdpi.data.subscription

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapSelectorMembershipTest {
    private fun node(tag: String) =
        """{"type":"shadowsocks","tag":"$tag","server":"$tag.example","server_port":443,
            "method":"aes-128-gcm","password":"fixture"}"""

    @Test
    fun `bootstrap stores only resolved members and their matching metadata ids`() =
        runBlocking {
            val payload =
                """{"outbounds":[${node("a")},${node("b")},
                  {"type":"selector","tag":"select","outbounds":["b"],"default":"b"}],
                  "ripdpi":{"schema_version":2,"cloudflare_outbound_tags":["a","b"],
                    "migration":{"schema_version":1,"migration_id":"11111111-1111-1111-1111-111111111111",
                    "delivery_id":"22222222-2222-2222-2222-222222222222","target_tags":["b"],
                    "receipt_key":"${"a".repeat(64)}","awg_previous_tags":{}}}}"""
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body(payload).build())
                server.start()
                val result =
                    BootstrapConsumer(
                        clockMillis = { 7L },
                    ).consume(server.url("/bootstrap/subset").toString(), "g")
                assertTrue(result is BootstrapConsumeResult.Consumed)
                result as BootstrapConsumeResult.Consumed
                assertEquals(listOf("b"), result.profiles.map { it.displayName })
                assertEquals(setOf(result.profiles.single().id), result.cloudflareMemberIds)
                val receipt =
                    receivedMigration(result.migration, result.payloadSha256, 7L, result.profiles, emptyList())
                assertEquals(result.profiles.single().id, receipt?.targets?.single()?.profileId)
            }
        }

    @Test
    fun `bootstrap retains selector order instead of concrete declaration order`() =
        runBlocking {
            val payload =
                """{"outbounds":[${node("a")},${node("b")},
                    {"type":"selector","tag":"select","outbounds":["b","a"],"default":"b"}]}"""
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body(payload).build())
                server.start()
                val result = BootstrapConsumer().consume(server.url("/bootstrap/order").toString(), "g")
                assertTrue(result is BootstrapConsumeResult.Consumed)
                assertEquals(
                    listOf("b", "a"),
                    (result as BootstrapConsumeResult.Consumed).profiles.map { it.displayName },
                )
            }
        }
}
