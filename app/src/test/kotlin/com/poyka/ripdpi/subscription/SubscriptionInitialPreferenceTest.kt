package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionInitialPreferenceTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `refresh persists a nested initial preference with the retained endpoint identity`() =
        runTest {
            val old =
                ProxyProfile.Shadowsocks(
                    "old-b",
                    "Old b",
                    "subscription-group",
                    "b.example",
                    443,
                    "aes-128-gcm",
                    "fixture",
                )
            val fixture = fixture(now = 3000L, initialMembers = listOf(old))
            val payload =
                """{"outbounds":[
                  {"type":"shadowsocks","tag":"a","server":"a.example","server_port":443,"method":"aes-128-gcm","password":"fixture"},
                  {"type":"shadowsocks","tag":"b","server":"b.example","server_port":443,"method":"aes-128-gcm","password":"fixture"},
                  {"type":"selector","tag":"inner","outbounds":["a","b"],"default":"b"},
                  {"type":"selector","tag":"select","outbounds":["inner"],"default":"inner"}]}"""
            server.enqueue(response(200, payload))
            assertTrue(fixture.coordinator.refresh("subscription-group") is SubscriptionRefreshResult.Updated)
            val saved = fixture.repository.list().single()
            assertEquals("old-b", saved.initialMemberId)
            assertEquals("b", saved.members.single { it.id == saved.initialMemberId }.displayName)
        }
}
