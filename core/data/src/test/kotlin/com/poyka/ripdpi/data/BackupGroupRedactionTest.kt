package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupSerializer
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for P1-7: a [BackupVariant.SHARE] export — documented "safe
 * to share publicly" — must not leak subscription secrets embedded in a
 * [ProxyGroup]. Before this fix, `groups` were written verbatim, exposing the
 * subscription [Subscription.token] bearer credential and any `user:pass@` /
 * `token=` material in [Subscription.link].
 */
class BackupGroupRedactionTest {
    private val subscriptionGroup =
        ProxyGroup(
            id = "g-sub-1",
            name = "Remote Sub",
            type = ProxyGroupType.SUBSCRIPTION,
            order = 0,
            isSelector = false,
            subscription =
                Subscription(
                    link = "https://user:s3cr3t@sub.example.com/sub/hash?token=bearer-abc&autoUpdate=true",
                    token = "fixture-token-xyz",
                    autoUpdate = true,
                    mirrors =
                        SubscriptionMirrorSet(
                            listOf(
                                SubscriptionMirror(
                                    "direct",
                                    "https://mirror.example/sub/private-device-path",
                                    "mirror-bearer-secret",
                                ),
                            ),
                        ),
                ),
        )

    private fun export(
        variant: BackupVariant,
        groups: List<ProxyGroup>,
    ) = BackupExporter.export(
        variant = variant,
        profiles = emptyList(),
        groups = groups,
        rules = emptyList(),
        settings = emptyMap(),
        createdAtEpochMillis = 0L,
        appVersion = "1.0.0",
    )

    @Test
    fun `SHARE export of subscription group removes token and complete link`() {
        val doc = export(BackupVariant.SHARE, listOf(subscriptionGroup))
        val sub = doc.groups.single().subscription!!

        assertTrue("SHARE must remove mirror credentials and per-device URLs", sub.mirrors.mirrors.isEmpty())
        assertEquals("token must be emptied in SHARE", "", sub.token)
        assertEquals("subscription URLs can carry credentials in any component", "", sub.link)
        assertFalse(doc.containsCredentials)
    }

    @Test
    fun `SHARE export preserves non-secret subscription metadata`() {
        val doc = export(BackupVariant.SHARE, listOf(subscriptionGroup))
        val group = doc.groups.single()

        assertEquals("g-sub-1", group.id)
        assertEquals(ProxyGroupType.SUBSCRIPTION, group.type)
        assertEquals("autoUpdate flag must survive", true, group.subscription!!.autoUpdate)
    }

    @Test
    fun `FULL export keeps subscription token and link verbatim`() {
        val doc = export(BackupVariant.FULL, listOf(subscriptionGroup))
        val sub = doc.groups.single().subscription!!

        assertEquals(subscriptionGroup.subscription!!.mirrors, sub.mirrors)
        assertEquals("fixture-token-xyz", sub.token)
        assertEquals(
            "https://user:s3cr3t@sub.example.com/sub/hash?token=bearer-abc&autoUpdate=true",
            sub.link,
        )
    }

    @Test
    fun `SHARE export leaves a group without a subscription untouched`() {
        val basic =
            ProxyGroup(
                id = "g-basic-1",
                name = "Basic",
                type = ProxyGroupType.BASIC,
                order = 1,
                isSelector = false,
                subscription = null,
            )
        val doc = export(BackupVariant.SHARE, listOf(basic))
        assertEquals(basic, doc.groups.single())
    }

    @Test
    fun `SHARE drops every URL credential carrier while FULL preserves it`() {
        val links =
            listOf(
                "https://sub.example.com/sub/bearer-path-secret",
                "https://sub.example.com/bootstrap/one-time-secret",
                "https://sub.example.com/sub/%62%65%61%72%65%72",
                "https://sub.example.com/sub?%74oken=encoded-key-secret",
                "https://sub.example.com/sub?unknown=opaque-query-secret",
                "https://sub.example.com/sub#fragment-secret",
                "https://user:password@sub.example.com/sub",
                "https://credential-host-secret.example.com/",
                "opaque:credential-secret",
                "not a uri ::: user:pass@oops",
                "",
            )
        links.forEach { link ->
            val group = subscriptionGroup.copy(subscription = subscriptionGroup.subscription!!.copy(link = link))
            val share = export(BackupVariant.SHARE, listOf(group))
            assertEquals(
                "SHARE must remove the complete link",
                "",
                share.groups
                    .single()
                    .subscription!!
                    .link,
            )
            assertFalse(share.containsCredentials)
            val full = export(BackupVariant.FULL, listOf(group))
            assertEquals(
                link,
                full.groups
                    .single()
                    .subscription!!
                    .link,
            )
            assertTrue(full.containsCredentials)
        }
    }

    @Test
    fun `SHARE export drops group members so per-node credentials never leak`() {
        // Subscription/selector member nodes can embed per-node credentials. They
        // are removed with the credential-bearing subscription link. FULL exports
        // keep both for private restore.
        val member =
            ProxyProfile.Trojan(
                id = "tr-member-1",
                displayName = "Sub Node",
                groupId = "g-sub-1",
                server = "node.example.com",
                serverPort = 443,
                password = "fixture-member-pw",
            )
        val group = subscriptionGroup.copy(members = listOf(member))

        val share = export(BackupVariant.SHARE, listOf(group)).groups.single()
        assertTrue("SHARE must drop the member node list", share.members.isEmpty())

        val full = export(BackupVariant.FULL, listOf(group)).groups.single()
        assertEquals("FULL must preserve members verbatim", listOf(member), full.members)
    }

    @Test
    fun `SHARE export drops package routing rules while FULL preserves them`() {
        val rule =
            PackageRoutingRule(
                packageName = "com.private.application",
                action = PackageRoutingAction.BYPASS,
                origin = PackageRoutingRuleOrigin.Subscription(subscriptionGroup.id),
            )
        val group = subscriptionGroup.copy(packageRoutingRules = listOf(rule))

        val share = export(BackupVariant.SHARE, listOf(group)).groups.single()
        assertTrue("SHARE must drop application package names", share.packageRoutingRules.isEmpty())

        val full = export(BackupVariant.FULL, listOf(group)).groups.single()
        assertEquals("FULL must preserve package rules verbatim", listOf(rule), full.packageRoutingRules)
    }

    @Test
    fun `SHARE omits signed payload controls even without members or a subscription`() {
        for (subscription in listOf(null, subscriptionGroup.subscription)) {
            val group = subscriptionGroup.copy(subscription = subscription, failover = signedFailover())
            val share = export(BackupVariant.SHARE, listOf(group))
            val encoded = BackupSerializer.encodeToString(share)

            assertNull(share.groups.single().failover)
            assertFalse(encoded.contains("fixture-primary-token"))
            assertFalse(encoded.contains("fixture-control-token"))
            assertFalse(encoded.contains("fixture-signed-control"))
            assertEquals(group.id, share.groups.single().id)
        }
    }

    @Test
    fun `FULL preserves complete payload policy and signed controls`() {
        val group = subscriptionGroup.copy(failover = signedFailover())
        val full = export(BackupVariant.FULL, listOf(group))

        assertEquals(group, full.groups.single())
        val encoded = BackupSerializer.encodeToString(full)
        assertTrue(encoded.contains("fixture-primary-token"))
        assertTrue(encoded.contains("fixture-control-token"))
        assertTrue(encoded.contains("fixture-signed-control"))
    }

    @Test
    fun `SHARE omits ordinary URL-test controls with credentials`() {
        val group = subscriptionGroup.copy(failover = signedFailover().copy(payloadPolicy = null))
        val share = export(BackupVariant.SHARE, listOf(group))

        assertNull(share.groups.single().failover)
        assertFalse(BackupSerializer.encodeToString(share).contains("fixture-primary-token"))
    }

    private fun signedFailover() =
        SelectorFailover(
            probeUrl = "https://primary.example/probe?token=fixture-primary-token",
            intervalSeconds = 30,
            toleranceMs = 0,
            payloadPolicy =
                PayloadFailoverPolicy(
                    schemaVersion = 1,
                    selectorTag = "select",
                    probes =
                        listOf(
                            PayloadProbeControl(
                                "https://control.example/payload?access_token=fixture-control-token",
                                32_768,
                                "a".repeat(64),
                            ),
                            PayloadProbeControl(
                                "https://independent.example/payload?X-Amz-Signature=fixture-signed-control",
                                32_768,
                                "b".repeat(64),
                            ),
                        ),
                    intervalSeconds = 30,
                    timeoutSeconds = 5,
                    failureThreshold = 2,
                    recoveryThreshold = 2,
                    cooldownSeconds = 60,
                    maxBackoffSeconds = 120,
                ),
        )
}
