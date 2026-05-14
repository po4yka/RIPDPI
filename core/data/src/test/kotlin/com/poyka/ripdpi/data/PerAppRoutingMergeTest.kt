package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingMerge
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import com.poyka.ripdpi.data.routing.PackageRoutingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PackageRoutingMerge]: imports subscription-derived per-app
 * routing rules into a [PackageRoutingStore] under a subscription-ID-tagged
 * namespace, surfaces conflicts with user-set rules without overwriting them,
 * and replaces a subscription's tagged rule set atomically on refresh.
 */
class PerAppRoutingMergeTest {
    private val subA = "sub-aaaa"
    private val subB = "sub-bbbb"

    private fun subRule(
        pkg: String,
        action: PackageRoutingAction,
    ) = PackageRoutingRule(
        packageName = pkg,
        action = action,
        origin = PackageRoutingRuleOrigin.Subscription(subA),
    )

    private fun userRule(
        pkg: String,
        action: PackageRoutingAction,
    ) = PackageRoutingRule(
        packageName = pkg,
        action = action,
        origin = PackageRoutingRuleOrigin.User,
    )

    @Test
    fun `clean import tags rules with the subscription id`() {
        val store = PackageRoutingStore.empty()
        val imported =
            listOf(
                subRule("com.a.app", PackageRoutingAction.BYPASS),
                subRule("com.b.app", PackageRoutingAction.VIA_TUN),
            )

        val result = PackageRoutingMerge.merge(store, subscriptionId = subA, importedRules = imported)

        assertTrue(result.conflicts.isEmpty())
        val merged = result.store
        assertEquals(2, merged.rules.size)
        assertTrue(merged.rules.all { it.origin == PackageRoutingRuleOrigin.Subscription(subA) })
    }

    @Test
    fun `removing the subscription removes exactly its rules and no others`() {
        val store = PackageRoutingStore.empty()
        val withUser =
            PackageRoutingMerge
                .merge(
                    store,
                    subscriptionId = subA,
                    importedRules = listOf(subRule("com.a.app", PackageRoutingAction.BYPASS)),
                ).store
                .upsertUserRule(userRule("com.user.app", PackageRoutingAction.VIA_TUN))
        // A second subscription contributes its own tagged rule.
        val withSubB =
            PackageRoutingMerge
                .merge(
                    withUser,
                    subscriptionId = subB,
                    importedRules =
                        listOf(
                            PackageRoutingRule(
                                packageName = "com.b.app",
                                action = PackageRoutingAction.BYPASS,
                                origin = PackageRoutingRuleOrigin.Subscription(subB),
                            ),
                        ),
                ).store

        val afterRemoval = withSubB.removeSubscriptionRules(subA)

        assertEquals(
            setOf("com.user.app", "com.b.app"),
            afterRemoval.rules.mapTo(mutableSetOf()) { it.packageName },
        )
    }

    @Test
    fun `a conflict with a user-set rule produces a conflict record and overwrites nothing`() {
        val store =
            PackageRoutingStore
                .empty()
                .upsertUserRule(userRule("com.shared.app", PackageRoutingAction.BYPASS))

        val result =
            PackageRoutingMerge.merge(
                store,
                subscriptionId = subA,
                importedRules = listOf(subRule("com.shared.app", PackageRoutingAction.VIA_TUN)),
            )

        assertEquals(1, result.conflicts.size)
        val conflict = result.conflicts.single()
        assertEquals("com.shared.app", conflict.packageName)
        assertEquals(PackageRoutingAction.BYPASS, conflict.userAction)
        assertEquals(PackageRoutingAction.VIA_TUN, conflict.subscriptionAction)
        // The user's rule is untouched; the subscription rule is NOT applied.
        val applied = result.store.rules.single { it.packageName == "com.shared.app" }
        assertEquals(PackageRoutingAction.BYPASS, applied.action)
        assertEquals(PackageRoutingRuleOrigin.User, applied.origin)
    }

    @Test
    fun `refresh replaces the subscription tagged set atomically`() {
        val firstImport =
            PackageRoutingMerge
                .merge(
                    PackageRoutingStore.empty(),
                    subscriptionId = subA,
                    importedRules =
                        listOf(
                            subRule("com.stale.app", PackageRoutingAction.BYPASS),
                            subRule("com.keep.app", PackageRoutingAction.VIA_TUN),
                        ),
                ).store
        // User adds a rule between imports.
        val withUserAddition =
            firstImport.upsertUserRule(userRule("com.user.app", PackageRoutingAction.BYPASS))

        // Refresh: com.stale.app dropped, com.keep.app retained, com.fresh.app new.
        val refreshed =
            PackageRoutingMerge
                .merge(
                    withUserAddition,
                    subscriptionId = subA,
                    importedRules =
                        listOf(
                            subRule("com.keep.app", PackageRoutingAction.VIA_TUN),
                            subRule("com.fresh.app", PackageRoutingAction.BYPASS),
                        ),
                ).store

        val subPackages =
            refreshed.rules
                .filter { it.origin == PackageRoutingRuleOrigin.Subscription(subA) }
                .mapTo(mutableSetOf()) { it.packageName }
        assertEquals(setOf("com.keep.app", "com.fresh.app"), subPackages)
        assertFalse(subPackages.contains("com.stale.app"))
        // The user rule added since the last import is preserved.
        assertTrue(
            refreshed.rules.any { it.packageName == "com.user.app" && it.origin == PackageRoutingRuleOrigin.User },
        )
    }

    @Test
    fun `refresh re-checks user rules added since the last import for conflicts`() {
        val firstImport =
            PackageRoutingMerge
                .merge(
                    PackageRoutingStore.empty(),
                    subscriptionId = subA,
                    importedRules = listOf(subRule("com.a.app", PackageRoutingAction.BYPASS)),
                ).store
        // The user adds a rule for a package the subscription will also claim on refresh.
        val withUserAddition =
            firstImport.upsertUserRule(userRule("com.contested.app", PackageRoutingAction.BYPASS))

        val refresh =
            PackageRoutingMerge.merge(
                withUserAddition,
                subscriptionId = subA,
                importedRules = listOf(subRule("com.contested.app", PackageRoutingAction.VIA_TUN)),
            )

        assertEquals(1, refresh.conflicts.size)
        assertEquals("com.contested.app", refresh.conflicts.single().packageName)
        // The user rule survived; the subscription did not silently win.
        val applied = refresh.store.rules.single { it.packageName == "com.contested.app" }
        assertEquals(PackageRoutingRuleOrigin.User, applied.origin)
    }

    @Test
    fun `diagnostics summary carries counts but never package names`() {
        val store =
            PackageRoutingStore
                .empty()
                .upsertUserRule(userRule("com.secret-user.app", PackageRoutingAction.BYPASS))
        val result =
            PackageRoutingMerge.merge(
                store,
                subscriptionId = subA,
                importedRules =
                    listOf(
                        subRule("com.secret-sub.app", PackageRoutingAction.VIA_TUN),
                        subRule("com.secret-user.app", PackageRoutingAction.VIA_TUN),
                    ),
            )

        val summary = result.toRedactedDiagnostics()

        assertFalse(summary.contains("com.secret-user.app"))
        assertFalse(summary.contains("com.secret-sub.app"))
        // Counts: 1 applied from sub, 1 user-set, 1 conflict.
        assertTrue(summary.contains("1"))
        assertTrue(summary.contains("from sub", ignoreCase = true))
        assertTrue(summary.contains("conflict", ignoreCase = true))
    }
}
