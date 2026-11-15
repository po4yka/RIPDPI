package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionEncodingRefreshTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `invalid UTF-8 response leaves working members and package routes intact`() =
        runTest {
            val bytes =
                "trojan://pa".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) +
                    "@new.example:443".toByteArray()
            assertRejectedWithoutReplacement(bytes)
        }

    @Test
    fun `invalid UTF-8 base64 credential leaves working members and package routes intact`() =
        runTest {
            val bytes =
                "trojan://pa".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) +
                    "@new.example:443".toByteArray()
            assertRejectedWithoutReplacement(Base64.getEncoder().encode(bytes))
        }

    private suspend fun assertRejectedWithoutReplacement(bytes: ByteArray) {
        val prior =
            ProxyProfile.Trojan(
                id = "prior",
                displayName = "Saved node",
                groupId = "subscription-group",
                server = "old.example",
                serverPort = RelayPort,
                password = "fixture-unchanged",
            )
        val rules =
            listOf(
                PackageRoutingRule(
                    packageName = "com.example.app",
                    action = PackageRoutingAction.BYPASS,
                    origin = PackageRoutingRuleOrigin.Subscription("subscription-group"),
                ),
            )
        val fixture = fixture(now = RefreshAt, initialMembers = listOf(prior), initialRules = rules)
        val before = fixture.repository.list().single()
        server.enqueue(
            MockResponse
                .Builder()
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .body(Buffer().write(bytes))
                .build(),
        )

        val result = fixture.coordinator.refresh(before.id)
        val after = fixture.repository.list().single()

        assertTrue(result is SubscriptionRefreshResult.Failed)
        assertEquals(SubscriptionRefreshFailure.PARSE_ERROR, (result as SubscriptionRefreshResult.Failed).failure)
        assertEquals(before.members, after.members)
        assertEquals(before.packageRoutingRules, after.packageRoutingRules)
        assertEquals(before.subscription!!.lastUpdated, after.subscription!!.lastUpdated)
    }

    private companion object {
        const val RelayPort = 443
        const val RefreshAt = 3_000L
    }
}
