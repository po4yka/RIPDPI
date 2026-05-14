package com.poyka.ripdpi.data.routing

import kotlinx.serialization.Serializable

/**
 * An immutable collection of [PackageRoutingRule] records — the per-app routing
 * policy. Every mutator returns a new [PackageRoutingStore]; the type is a
 * value object so callers can diff before/after states cheaply.
 *
 * At most one rule may exist per `packageName`. User-authored rules and
 * subscription-imported rules share the same flat namespace but are
 * distinguished by [PackageRoutingRule.origin]; conflict resolution between the
 * two lives in [PackageRoutingMerge], not here.
 */
@Serializable
data class PackageRoutingStore(
    val rules: List<PackageRoutingRule> = emptyList(),
) {
    /**
     * Inserts or replaces the user-authored rule for [rule]'s package. Any
     * existing rule for that package — whatever its origin — is replaced. Use
     * this for direct edits on the per-app routing screen.
     */
    fun upsertUserRule(rule: PackageRoutingRule): PackageRoutingStore {
        require(rule.origin == PackageRoutingRuleOrigin.User) {
            "upsertUserRule expects a User-origin rule, got ${rule.origin}"
        }
        return PackageRoutingStore(rules.filterNot { it.packageName == rule.packageName } + rule)
    }

    /** Removes the rule for [packageName] regardless of origin. No-op when absent. */
    fun removeRule(packageName: String): PackageRoutingStore =
        PackageRoutingStore(rules.filterNot { it.packageName == packageName })

    /**
     * Removes every rule tagged with [subscriptionId]; user-authored rules and
     * rules from other subscriptions are left untouched. Called when a
     * subscription group is deleted.
     */
    fun removeSubscriptionRules(subscriptionId: String): PackageRoutingStore =
        PackageRoutingStore(
            rules.filterNot {
                it.origin == PackageRoutingRuleOrigin.Subscription(subscriptionId)
            },
        )

    /** All rules imported from the subscription identified by [subscriptionId]. */
    fun subscriptionRules(subscriptionId: String): List<PackageRoutingRule> =
        rules.filter { it.origin == PackageRoutingRuleOrigin.Subscription(subscriptionId) }

    /** All user-authored rules. */
    fun userRules(): List<PackageRoutingRule> = rules.filter { it.origin == PackageRoutingRuleOrigin.User }

    companion object {
        /** An empty store with no rules. */
        fun empty(): PackageRoutingStore = PackageRoutingStore()
    }
}
