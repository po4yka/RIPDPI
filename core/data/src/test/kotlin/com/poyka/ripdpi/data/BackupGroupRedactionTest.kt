package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupGroupRedactor
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `SHARE export of subscription group empties token and scrubs link`() {
        val doc = export(BackupVariant.SHARE, listOf(subscriptionGroup))
        val sub = doc.groups.single().subscription!!

        assertTrue("SHARE must remove mirror credentials and per-device URLs", sub.mirrors.mirrors.isEmpty())
        assertEquals("token must be emptied in SHARE", "", sub.token)
        assertFalse("link must not retain userinfo credentials", sub.link.contains("s3cr3t"))
        assertFalse("link must not retain the bearer token param", sub.link.contains("bearer-abc"))
        assertFalse("link must not retain the username userinfo", sub.link.contains("user:"))
        // Non-secret structure is preserved so the share remains useful context.
        assertTrue("host should survive scrubbing", sub.link.contains("sub.example.com"))
        assertTrue("non-secret query param should survive", sub.link.contains("autoUpdate=true"))
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
    fun `scrubLink drops userinfo and secret query params but keeps the rest`() {
        val scrubbed =
            BackupGroupRedactor.scrubLink(
                "https://alice:hunter2@host.example.com/sub/x?access_token=abc&keep=1",
            )
        assertFalse(scrubbed.contains("hunter2"))
        assertFalse(scrubbed.contains("alice"))
        assertFalse(scrubbed.contains("access_token"))
        assertTrue(scrubbed.contains("host.example.com"))
        assertTrue(scrubbed.contains("keep=1"))
    }

    @Test
    fun `scrubLink empties an unparseable link rather than risk a leak`() {
        // A value that is not a valid URI is treated as opaque and dropped.
        assertEquals("", BackupGroupRedactor.scrubLink("not a uri ::: user:pass@oops"))
    }

    @Test
    fun `scrubLink preserves an already-clean link`() {
        val clean = "https://sub.example.com/sub/hash?autoUpdate=true"
        assertEquals(clean, BackupGroupRedactor.scrubLink(clean))
    }

    @Test
    fun `SHARE export drops group members so per-node credentials never leak`() {
        // Subscription/selector member nodes can embed per-node credentials. They
        // are re-fetchable from the (scrubbed) subscription link, so SHARE drops
        // them entirely rather than risk leaking a node password publicly.
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
}
